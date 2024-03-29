use crate as aurum;
use crate::cluster::{
  ClusterConfig, ClusterEvent, ClusterUpdate, Gossip, HBRConfig, HeartbeatReceiver,
  HeartbeatReceiverMsg, MachineState, Member, NodeRing, FAILURE_MODE, LOG_LEVEL,
};
use crate::core::{
  Actor, ActorContext, ActorRef, ActorSignal, Destination, LocalRef, Node, UdpSerial, UnifiedType,
};
use crate::testkit::FailureConfigMap;
use crate::{debug, info, trace, AurumInterface};
use async_trait::async_trait;
use im;
use itertools::Itertools;
use maplit::{btreemap, hashset};
use rand::seq::IteratorRandom;
use serde::{Deserialize, Serialize};
use std::collections::btree_map::Entry;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tokio::task::JoinHandle;
use IntraClusterMsg::*;
use MachineState::*;

#[derive(AurumInterface)]
#[aurum(local)]
pub enum ClusterMsg<U: UnifiedType> {
  #[aurum]
  IntraMsg(IntraClusterMsg<U>),
  #[aurum(local)]
  LocalCmd(ClusterCmd),
  PingTimeout,
  GossipTimeout,
  Downed(Arc<Member>),
  HeartbeatTick,
}

#[derive(Serialize, Deserialize)]
#[serde(bound = "U: UnifiedType")]
pub enum IntraClusterMsg<U: UnifiedType> {
  Foo(ActorRef<U, IntraClusterMsg<U>>),
  ReqHeartbeat(Arc<Member>, u64),
  ReqGossip(Arc<Member>),
  State(Gossip),
  Ping(Arc<Member>),
}

pub enum ClusterCmd {
  Subscribe(LocalRef<ClusterUpdate>),
  FailureMap(FailureConfigMap),
}

struct InCluster {
  charges: HashMap<Arc<Member>, LocalRef<HeartbeatReceiverMsg>>,
  managers: Vec<Arc<Member>>,
  gossip: Gossip,
  members: im::HashSet<Arc<Member>>,
  ring: NodeRing,
  gossip_timeout: JoinHandle<bool>,
}
impl InCluster {
  fn alone<U: UnifiedType>(
    common: &NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
  ) -> InCluster {
    let mut ring = NodeRing::new(common.clr_config.replication_factor);
    ring.insert(common.member.clone());
    InCluster {
      charges: HashMap::new(),
      managers: Vec::new(),
      gossip: Gossip {
        states: btreemap! {common.member.clone() => Up},
      },
      members: im::hashset![common.member.clone()],
      ring: ring,
      gossip_timeout: ctx.node.rt().spawn(async { true }),
    }
  }

  async fn process<U: UnifiedType>(
    &mut self,
    common: &mut NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
    msg: IntraClusterMsg<U>,
  ) -> Option<State> {
    match msg {
      Foo(_) => None,
      ReqGossip(member) => {
        let log = format!("got gossip req from {}", member.socket.udp);
        debug!(LOG_LEVEL, ctx.node, log);
        let ser = Arc::new(UdpSerial::msg(&common.clr_dest, &State(self.gossip.clone())));
        ctx.node.udp_select(&member.socket, &ser, FAILURE_MODE, &common.fail_map).await;
        None
      }
      ReqHeartbeat(member, id) => {
        // TODO: What if requester is not the manager?
        // For now, send heartbeat anyway. Conflicts will reconcile eventually.
        if id == common.member.id {
          common.heartbeat(ctx, &member).await;
        } else {
          let log = format!("HB req for id {}, id is {}", common.member.id, id);
          debug!(LOG_LEVEL, ctx.node, log);
        }
        None
      }
      State(gossip) => {
        self.gossip_timeout.abort();
        let mut events = self.gossip.merge(gossip);
        let mut new_self_member = None;
        let disperse = !events.is_empty();
        for e in &events {
          match e {
            ClusterEvent::Added(member) => {
              self.ring.insert(member.clone());
              self.members.insert(member.clone());
            }
            ClusterEvent::Removed(member) => {
              self.ring.remove(&*member).unwrap();
              self.members.remove(member).unwrap();
              if *member == common.member {
                common.new_id(ctx);
                self.ring.insert(common.member.clone());
                self.members.insert(common.member.clone());
                self.gossip.states.insert(common.member.clone(), Up);
                new_self_member = Some(ClusterEvent::Joined(common.member.clone()));
              }
            }
            _ => {}
          }
        }
        new_self_member.into_iter().for_each(|e| events.push(e));
        if disperse {
          self.gossip_round(common, ctx, hashset!()).await;
        }
        self.update_charges_managers(common, ctx);
        self.notify(common, events);
        self.gossip_timeout = common.schedule_gossip_timeout(ctx);
        None
      }
      Ping(member) => {
        let log = format!("received ping from {:?}", member);
        info!(LOG_LEVEL, ctx.node, log);
        if let Entry::Vacant(v) = self.gossip.states.entry(member.clone()) {
          v.insert(Up);
          self.ring.insert(member.clone());
          self.members.insert(member.clone());
          self.update_charges_managers(common, ctx);
          self.notify(common, vec![ClusterEvent::Added(member.clone())]);
        } else {
          info!(LOG_LEVEL, ctx.node, format!("pinger already exists"));
        }
        self.gossip_round(common, ctx, hashset!(&member)).await;
        None
      }
    }
  }

  fn update_charges_managers<U: UnifiedType>(
    &mut self,
    common: &NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
  ) {
    trace!(
      LOG_LEVEL,
      ctx.node,
      format!(
        "old charges: {:?}",
        self.charges.keys().map(|m| (m.socket.udp, m.id)).collect_vec()
      )
    );
    let mut new_charges = HashMap::new();
    for member in self.ring.charges(&common.member).unwrap() {
      let hbr = self
        .charges
        .remove(&member)
        .unwrap_or_else(|| HeartbeatReceiver::spawn(ctx, common, member.clone()));
      new_charges.insert(member, hbr);
    }
    for (_, hbr) in self.charges.iter() {
      hbr.signal(ActorSignal::Term);
    }
    self.charges = new_charges;
    trace!(
      LOG_LEVEL,
      ctx.node,
      format!(
        "new charges: {:?}",
        self.charges.keys().map(|m| (m.socket.udp, m.id)).collect_vec()
      )
    );
    trace!(
      LOG_LEVEL,
      ctx.node,
      format!(
        "old managers: {:?}",
        self.managers.iter().map(|m| (m.socket.udp, m.id)).collect_vec()
      )
    );
    self.managers = self.ring.node_managers(&common.member).unwrap();
    trace!(
      LOG_LEVEL,
      ctx.node,
      format!(
        "old managers: {:?}",
        self.managers.iter().map(|m| (m.socket.udp, m.id)).collect_vec()
      )
    );
  }

  async fn gossip_round<U: UnifiedType>(
    &self,
    common: &NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
    mut guaranteed: HashSet<&Arc<Member>>,
  ) {
    self.managers.iter().for_each(|m| {
      guaranteed.insert(m);
    });
    self.charges.keys().for_each(|m| {
      guaranteed.insert(m);
    });
    guaranteed.insert(&common.member);
    self
      .members
      .iter()
      .filter(|m| !guaranteed.contains(*m))
      .choose_multiple(&mut rand::thread_rng(), common.clr_config.gossip_disperse)
      .into_iter()
      .for_each(|m| {
        guaranteed.insert(m);
      });
    guaranteed.remove(&common.member);
    debug!(
      LOG_LEVEL,
      ctx.node,
      format!("gossiping to {:?}", guaranteed.iter().map(|m| m.socket.udp).collect_vec())
    );
    let ser = Arc::new(UdpSerial::msg(&common.clr_dest, &State(self.gossip.clone())));
    for member in guaranteed {
      ctx.node.udp_select(&member.socket, &ser, FAILURE_MODE, &common.fail_map).await;
    }
  }

  async fn gossip_reqs<U: UnifiedType>(
    &self,
    common: &NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
  ) {
    debug!(
      LOG_LEVEL,
      ctx.node,
      format!(
        "gossip state: {:?}",
        self.gossip.states.iter().map(|(m, s)| (m.socket.udp, s)).collect_vec()
      )
    );
    let recipients = self
      .members
      .iter()
      .filter(|m| (**m) != common.member)
      .choose_multiple(&mut rand::thread_rng(), common.clr_config.gossip_disperse)
      .into_iter()
      .collect_vec();
    debug!(
      LOG_LEVEL,
      ctx.node,
      format!("gossip reqs sent to: {:?}", recipients.iter().map(|m| m.socket.udp).collect_vec())
    );
    let ser = Arc::new(UdpSerial::msg(&common.clr_dest, &ReqGossip(common.member.clone())));
    for member in recipients {
      ctx.node.udp_select(&member.socket, &ser, FAILURE_MODE, &common.fail_map).await;
    }
  }

  async fn down<U: UnifiedType>(
    &mut self,
    common: &mut NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
    member: Arc<Member>,
  ) {
    let state = self.gossip.states.get_mut(&member).unwrap();
    if *state == Up {
      *state = Down;
      self.ring.remove(&member).unwrap();
      self.members.remove(&member).unwrap();
      self.update_charges_managers(common, ctx);
      self.gossip_round(common, ctx, hashset!(&member)).await;
      self.notify(common, vec![ClusterEvent::Removed(member)]);
    }
  }

  fn notify<U: UnifiedType>(&self, common: &mut NodeState<U>, events: Vec<ClusterEvent>) {
    if !events.is_empty() {
      let msg = ClusterUpdate {
        events: events,
        nodes: self.members.clone(),
        ring: self.ring.clone(),
      };
      common.subscribers.retain(|s| s.send(msg.clone()));
    }
  }
}

struct Pinging {
  count: usize,
  timeout: JoinHandle<()>,
}
impl Pinging {
  async fn ping<U: UnifiedType>(
    &mut self,
    common: &NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
  ) {
    self.count -= 1;
    let log = format!("starting ping. {} attempts left", self.count);
    info!(LOG_LEVEL, ctx.node, log);
    let ser = Arc::new(UdpSerial::msg(&common.clr_dest, &Ping(common.member.clone())));
    for s in common.clr_config.seed_nodes.iter() {
      ctx.node.udp_select(s, &ser, FAILURE_MODE, &common.fail_map).await;
    }
    let ar = ctx.local_interface();
    self.timeout = ctx.node.schedule(common.clr_config.ping_timeout, move || {
      ar.send(ClusterMsg::PingTimeout);
    });
  }

  async fn process<U: UnifiedType>(
    &mut self,
    common: &mut NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
    msg: IntraClusterMsg<U>,
  ) -> Option<State> {
    match msg {
      // Getting this message means the response to our ping wasn't received.
      ReqHeartbeat(member, id) => {
        debug!(LOG_LEVEL, ctx.node, format!("Got HB request while pinging"));
        if id == common.member.id {
          common.heartbeat(ctx, &member).await;
        } else {
          let log = format!("HB req for id {}, id is {}", common.member.id, id);
          debug!(LOG_LEVEL, ctx.node, log);
        }
        let ser = Arc::new(UdpSerial::msg(&common.clr_dest, &ReqGossip(common.member.clone())));
        ctx.node.udp_select(&member.socket, &ser, FAILURE_MODE, &common.fail_map).await;
        None
      }
      State(mut gossip) => {
        let me = gossip.states.get(&common.member);
        let downed = me.filter(|s| s >= &&Down).is_some();
        if downed {
          common.new_id(ctx);
        }
        if me.is_none() || downed {
          gossip.states.insert(common.member.clone(), Up);
        }
        let mut ring = NodeRing::new(common.clr_config.replication_factor);
        let mut members = im::HashSet::new();
        gossip.states.iter().filter(|(_, s)| s < &&Down).map(|(m, _)| m.clone()).for_each(
          |member| {
            ring.insert(member.clone());
            members.insert(member);
          },
        );
        let mut ic = InCluster {
          charges: HashMap::new(),
          managers: Vec::new(),
          gossip: gossip,
          members: members,
          ring: ring,
          gossip_timeout: common.schedule_gossip_timeout(ctx),
        };
        ic.update_charges_managers(common, ctx);
        ic.gossip_round(common, ctx, hashset!()).await;
        ic.notify(common, vec![ClusterEvent::Joined(common.member.clone())]);
        debug!(
          LOG_LEVEL,
          ctx.node,
          format!(
            "responsible for {:?}",
            ic.charges.keys().map(|m| (m.socket.udp, m.id)).collect_vec()
          )
        );
        Some(State::InCluster(ic))
      }
      Ping(member) => {
        if !common.clr_config.seed_nodes.contains(&member.socket) {
          let log = format!("Got ping from {}, ignoring", member.socket);
          info!(LOG_LEVEL, ctx.node, log);
          return None;
        }
        let log = format!("Got ping from seed {}, creating cluster", member.socket);
        let gossip = Gossip {
          states: btreemap! {
            member => Up,
            common.member.clone() => Up
          },
        };
        let mut ring = NodeRing::new(common.clr_config.replication_factor);
        let mut members = im::HashSet::new();
        for m in gossip.states.keys() {
          ring.insert(m.clone());
          members.insert(m.clone());
        }
        let mut ic = InCluster {
          charges: HashMap::new(),
          managers: Vec::new(),
          gossip: gossip,
          members: members,
          ring: ring,
          gossip_timeout: common.schedule_gossip_timeout(ctx),
        };
        ic.update_charges_managers(common, ctx);
        ic.gossip_round(common, ctx, hashset!()).await;
        ic.notify(common, vec![ClusterEvent::Joined(common.member.clone())]);
        info!(LOG_LEVEL, ctx.node, log);
        Some(State::InCluster(ic))
      }
      _ => None,
    }
  }
}

enum State {
  InCluster(InCluster),
  Pinging(Pinging),
  Left,
}
impl State {
  async fn process<U: UnifiedType>(
    &mut self,
    common: &mut NodeState<U>,
    ctx: &ActorContext<U, ClusterMsg<U>>,
    msg: IntraClusterMsg<U>,
  ) {
    let new_state = match self {
      State::InCluster(ref mut state) => state.process(common, ctx, msg).await,
      State::Pinging(ref mut state) => state.process(common, ctx, msg).await,
      State::Left => None,
    };
    if let Some(s) = new_state {
      *self = s;
    }
  }
}

pub(crate) struct NodeState<U: UnifiedType> {
  pub member: Arc<Member>,
  pub clr_dest: Destination<U, IntraClusterMsg<U>>,
  pub hbr_dest: Destination<U, HeartbeatReceiverMsg>,
  pub subscribers: Vec<LocalRef<ClusterUpdate>>,
  pub fail_map: FailureConfigMap,
  pub hb_interval_changes: u32,
  pub clr_config: ClusterConfig,
  pub hbr_config: HBRConfig,
}
impl<U: UnifiedType> NodeState<U> {
  fn new_id(&mut self, ctx: &ActorContext<U, ClusterMsg<U>>) {
    let old_id = self.member.id;
    self.member = Arc::new(Member {
      socket: self.member.socket.clone(),
      id: rand::random(),
      vnodes: self.member.vnodes,
    });
    self.hbr_dest = Destination::new::<HeartbeatReceiverMsg>(HeartbeatReceiver::<U>::from_clr(
      self.clr_dest.name().name().as_str(),
      self.member.id,
    ));
    let log = format!("Was downed, id {} -> {}", old_id, self.member.id);
    info!(LOG_LEVEL, ctx.node, log);
  }

  async fn heartbeat(&self, ctx: &ActorContext<U, ClusterMsg<U>>, member: &Member) {
    let msg =
      HeartbeatReceiverMsg::Heartbeat(self.clr_config.hb_interval, self.hb_interval_changes);
    let ser = Arc::new(UdpSerial::msg(&self.hbr_dest, &msg));
    ctx.node.udp_select(&member.socket, &ser, FAILURE_MODE, &self.fail_map).await;
  }

  fn schedule_gossip_timeout(&self, ctx: &ActorContext<U, ClusterMsg<U>>) -> JoinHandle<bool> {
    ctx.node.schedule_local_msg(
      self.clr_config.gossip_timeout,
      ctx.local_interface(),
      ClusterMsg::GossipTimeout,
    )
  }
}

pub struct Cluster<U: UnifiedType> {
  common: NodeState<U>,
  state: State,
}
#[async_trait]
impl<U: UnifiedType> Actor<U, ClusterMsg<U>> for Cluster<U> {
  async fn pre_start(&mut self, ctx: &ActorContext<U, ClusterMsg<U>>) {
    let log = format!("STARTING member with id: {}", self.common.member.id);
    info!(LOG_LEVEL, ctx.node, log);
    if self.common.clr_config.seed_nodes.is_empty() {
      self.create_cluster(ctx);
    } else {
      let mut png = Pinging {
        count: self.common.clr_config.num_pings,
        timeout: ctx.node.rt().spawn(async {}),
      };
      png.ping(&self.common, ctx).await;
      self.state = State::Pinging(png);
    }
    ctx.node.schedule_local_msg(
      self.common.clr_config.hb_interval,
      ctx.local_interface(),
      ClusterMsg::HeartbeatTick,
    );
  }

  async fn recv(&mut self, ctx: &ActorContext<U, ClusterMsg<U>>, msg: ClusterMsg<U>) {
    match msg {
      ClusterMsg::IntraMsg(msg) => {
        self.state.process(&mut self.common, ctx, msg).await;
      }
      ClusterMsg::LocalCmd(ClusterCmd::Subscribe(subr)) => {
        if let State::InCluster(ic) = &self.state {
          let nodes = ic.members.clone();
          let ring = ic.ring.clone();
          let event = if nodes.len() == 1 {
            ClusterEvent::Alone(self.common.member.clone())
          } else {
            ClusterEvent::Joined(self.common.member.clone())
          };
          subr.send(ClusterUpdate {
            events: vec![event],
            nodes: nodes,
            ring: ring,
          });
        }
        self.common.subscribers.push(subr);
      }
      ClusterMsg::LocalCmd(ClusterCmd::FailureMap(map)) => {
        self.common.fail_map = map;
      }
      ClusterMsg::PingTimeout => {
        if let State::Pinging(png) = &mut self.state {
          if png.count != 0 {
            png.ping(&self.common, ctx).await;
          } else {
            info!(LOG_LEVEL, ctx.node, "Last ping timed out, starting cluster");
            self.create_cluster(ctx);
          }
        }
      }
      ClusterMsg::GossipTimeout => {
        if let State::InCluster(ic) = &self.state {
          ic.gossip_round(&self.common, ctx, hashset!()).await;
          ic.gossip_reqs(&self.common, ctx).await;
          self.common.schedule_gossip_timeout(ctx);
        }
      }
      ClusterMsg::Downed(member) => {
        if let State::InCluster(ic) = &mut self.state {
          ic.down(&mut self.common, ctx, member).await;
        }
      }
      ClusterMsg::HeartbeatTick => {
        if let State::InCluster(ic) = &mut self.state {
          for member in ic.managers.iter() {
            self.common.heartbeat(ctx, member).await;
          }
        }
        ctx.node.schedule_local_msg(
          self.common.clr_config.hb_interval,
          ctx.local_interface(),
          ClusterMsg::HeartbeatTick,
        );
      }
    }
  }

  async fn post_stop(&mut self, _: &ActorContext<U, ClusterMsg<U>>) {
    if let State::InCluster(ic) = &self.state {
      for charge in ic.charges.values() {
        charge.signal(ActorSignal::Term);
      }
    }
  }
}
impl<U: UnifiedType> Cluster<U> {
  pub fn new(
    node: &Node<U>,
    name: String,
    subrs: Vec<LocalRef<ClusterUpdate>>,
    fail_map: FailureConfigMap,
    mut clr_config: ClusterConfig,
    hbr_config: HBRConfig,
  ) -> LocalRef<ClusterCmd> {
    let id = rand::random();
    let double = clr_config.double;
    clr_config.seed_nodes.retain(|s| s != node.socket());
    let c = Cluster {
      common: NodeState {
        member: Arc::new(Member {
          socket: node.socket().clone(),
          id: id,
          vnodes: clr_config.vnodes,
        }),
        clr_dest: Destination::new::<ClusterMsg<U>>(name.clone()),
        hbr_dest: Destination::new::<HeartbeatReceiverMsg>(HeartbeatReceiver::<U>::from_clr(
          name.as_str(),
          id,
        )),
        subscribers: subrs,
        fail_map: fail_map,
        hb_interval_changes: 0,
        clr_config: clr_config,
        hbr_config: hbr_config,
      },
      state: State::Left,
    };
    node.spawn(double, c, name, true).local().clone().unwrap().transform()
  }

  fn create_cluster(&mut self, ctx: &ActorContext<U, ClusterMsg<U>>) {
    let ic = InCluster::alone(&self.common, ctx);
    let mem = self.common.member.clone();
    ic.notify(&mut self.common, vec![ClusterEvent::Alone(mem)]);
    self.state = State::InCluster(ic);
  }
}
