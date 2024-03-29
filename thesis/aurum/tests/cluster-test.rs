use async_trait::async_trait;
use aurum::cluster::{
  Cluster, ClusterCmd, ClusterConfig, ClusterEvent, ClusterUpdate, HBRConfig, Member,
};
use aurum::core::{Actor, ActorContext, ActorSignal, Host, LocalRef, Node, NodeConfig, Socket};
use aurum::testkit::{FailureConfigMap, LogLevel, LoggerMsg};
use aurum::{unify, AurumInterface};
use itertools::Itertools;
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc::{channel, Sender};
use CoordinatorMsg::*;

unify!(ClusterTestTypes = CoordinatorMsg | ClusterClientMsg);

const HOST: Host = Host::IP(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));

#[derive(Clone)]
struct NodeSet(u16, im::HashSet<Socket>, Vec<u16>);

struct TestNode {
  recvr: LocalRef<ClusterClientMsg>,
  node: Node<ClusterTestTypes>,
}

#[derive(AurumInterface, Clone)]
#[aurum(local)]
enum CoordinatorMsg {
  #[aurum(local)]
  Nodes(NodeSet),
  Kill(u16),
  Spawn(u16, Vec<u16>),
  WaitForConvergence,
  Done,
}

struct Coordinator {
  clr_cfg: ClusterConfig,
  hbr_cfg: HBRConfig,
  fail_map: FailureConfigMap,
  nodes: HashMap<u16, TestNode>,
  convergence: im::HashSet<Socket>,
  converged: HashSet<u16>,
  queue: Vec<CoordinatorMsg>,
  waiting: bool,
  notification: Sender<()>,
}
impl Coordinator {
  fn convergence_reached(&self) -> bool {
    self.nodes.keys().all(|k| self.converged.contains(k))
  }
}
#[async_trait]
impl Actor<ClusterTestTypes, CoordinatorMsg> for Coordinator {
  async fn recv(
    &mut self,
    ctx: &ActorContext<ClusterTestTypes, CoordinatorMsg>,
    msg: CoordinatorMsg,
  ) {
    match msg {
      Nodes(NodeSet(port, members, charges)) => {
        if members == self.convergence {
          self.converged.insert(port);
        } else {
          self.converged.remove(&port);
        }

        println!("{} MEMBERS - {:?}", port, members.iter().map(|x| x.udp).sorted().collect_vec());
        println!("{} CHARGES - {:?}", port, charges);

        if self.waiting && self.convergence_reached() {
          println!("CONVERGENCE reached!");
          self.waiting = false;
          let my_ref = ctx.local_interface();
          for msg in self.queue.drain(..) {
            my_ref.send(msg);
          }
        }
      }
      Kill(port) => {
        if self.waiting {
          self.queue.push(Kill(port));
          return;
        }
        println!("KILLING node on port {}", port);
        let node = self.nodes.remove(&port).unwrap();
        node.node.log(LoggerMsg::SetLevel(LogLevel::Off));
        node.recvr.signal(ActorSignal::Term);
        self.convergence.remove(&Socket::new(HOST.clone(), port, 0));
        self.converged.clear();
      }
      Spawn(port, seeds) => {
        if self.waiting {
          self.queue.push(Spawn(port, seeds));
          return;
        }
        let socket = Socket::new(HOST.clone(), port, 0);
        let mut config = NodeConfig::default();
        config.socket = socket.clone();
        let node = Node::<ClusterTestTypes>::new(config).await.unwrap();
        let mut clr_cfg = self.clr_cfg.clone();
        clr_cfg.seed_nodes = seeds.iter().map(|p| Socket::new(HOST.clone(), *p, 0)).collect();
        let cluster = Cluster::new(
          &node,
          "test-crdt-cluster".to_string(),
          vec![],
          self.fail_map.clone(),
          clr_cfg,
          self.hbr_cfg.clone(),
        );
        let recvr = ClusterClient {
          supervisor: ctx.local_interface(),
          cluster: cluster.clone(),
          member: Arc::new(Member::default()),
        };
        let recvr = node.spawn(false, recvr, "".to_string(), false).local().clone().unwrap();
        let entry = TestNode {
          recvr: recvr,
          node: node,
        };
        self.nodes.insert(port, entry);
        self.convergence.insert(socket);
        self.converged.clear();
      }
      WaitForConvergence => {
        if self.waiting {
          self.queue.push(WaitForConvergence);
          return;
        }
        if !self.convergence_reached() {
          println!("Waiting for CONVERGENCE");
          self.waiting = true;
          self.queue.clear();
        } else {
          println!("CONVERGENCE already reached");
        }
      }
      Done => {
        if self.waiting {
          self.queue.push(Done);
          return;
        }
        if self.convergence_reached() {
          println!("Done!");
          self.notification.send(()).await.unwrap();
        } else {
          println!("Waiting for CONVERGENCE");
          self.waiting = true;
          self.queue.clear();
          self.queue.push(Done);
        }
      }
    }
  }
}

#[derive(AurumInterface)]
#[aurum(local)]
enum ClusterClientMsg {
  #[aurum(local)]
  Updates(ClusterUpdate),
}

struct ClusterClient {
  supervisor: LocalRef<NodeSet>,
  cluster: LocalRef<ClusterCmd>,
  member: Arc<Member>,
}
#[async_trait]
impl Actor<ClusterTestTypes, ClusterClientMsg> for ClusterClient {
  async fn pre_start(&mut self, ctx: &ActorContext<ClusterTestTypes, ClusterClientMsg>) {
    self.cluster.send(ClusterCmd::Subscribe(ctx.local_interface()));
  }

  async fn recv(
    &mut self,
    ctx: &ActorContext<ClusterTestTypes, ClusterClientMsg>,
    msg: ClusterClientMsg,
  ) {
    match msg {
      ClusterClientMsg::Updates(mut update) => {
        self.member = match update.events.pop().unwrap() {
          ClusterEvent::Alone(m) => m,
          ClusterEvent::Joined(m) => m,
          _ => self.member.clone(),
        };
        let sockets = update.nodes.into_iter().map(|m| m.socket.clone()).collect();
        let charges = update
          .ring
          .charges(&self.member)
          .unwrap()
          .into_iter()
          .map(|m| m.socket.udp)
          .sorted()
          .collect_vec();
        self.supervisor.send(NodeSet(ctx.node.socket().udp, sockets, charges));
      }
    }
  }

  async fn post_stop(&mut self, _: &ActorContext<ClusterTestTypes, ClusterClientMsg>) {
    self.cluster.signal(ActorSignal::Term);
  }
}

fn run_cluster_test(
  events: Vec<CoordinatorMsg>,
  fail_map: FailureConfigMap,
  clr_cfg: ClusterConfig,
  hbr_cfg: HBRConfig,
  timeout: Duration,
  port: u16,
) {
  let socket = Socket::new(Host::DNS("127.0.0.1".to_string()), port, 0);
  let mut config = NodeConfig::default();
  config.socket = socket.clone();
  let node = Node::<ClusterTestTypes>::new_sync(config).unwrap();
  let (tx, mut rx) = channel(1);
  let actor = Coordinator {
    clr_cfg: clr_cfg,
    hbr_cfg: hbr_cfg,
    fail_map: fail_map,
    nodes: HashMap::new(),
    convergence: im::hashset![],
    converged: HashSet::new(),
    queue: Vec::new(),
    waiting: false,
    notification: tx,
  };
  let coor = node.spawn(false, actor, "".to_string(), false).local().clone().unwrap();
  for e in events {
    coor.send(e);
  }
  node.rt().block_on(async { tokio::time::timeout(timeout, rx.recv()).await.unwrap().unwrap() });
}

fn cluster_complete(
  fail_map: FailureConfigMap,
  clr_cfg: ClusterConfig,
  hbr_cfg: HBRConfig,
  timeout: Duration,
  port: u16,
) {
  let events = vec![
    Spawn(port + 1, vec![]),
    Spawn(port + 2, vec![port + 1]),
    Spawn(port + 3, vec![port + 2]),
    Spawn(port + 4, vec![port + 3]),
    Spawn(port + 5, vec![port + 4]),
    WaitForConvergence,
    Spawn(port + 6, vec![port + 5]),
    Spawn(port + 7, vec![port + 6]),
    Spawn(port + 8, vec![port + 7]),
    Spawn(port + 9, vec![port + 8]),
    Spawn(port + 10, vec![port + 9]),
    WaitForConvergence,
    Kill(port + 1),
    WaitForConvergence,
    Kill(port + 2),
    Kill(port + 3),
    Kill(port + 4),
    WaitForConvergence,
    Kill(port + 5),
    Kill(port + 6),
    Kill(port + 7),
    Kill(port + 8),
    Kill(port + 9),
    WaitForConvergence,
    Done,
  ];
  run_cluster_test(events, fail_map, clr_cfg, hbr_cfg, timeout, port);
}

#[test]
fn cluster_test_perfect() {
  let fail_map = FailureConfigMap::default();
  let mut clr_cfg = ClusterConfig::default();
  clr_cfg.num_pings = 20;
  clr_cfg.ping_timeout = Duration::from_millis(50);
  clr_cfg.vnodes = 1;
  let mut hbr_cfg = HBRConfig::default();
  hbr_cfg.req_tries = 1;
  hbr_cfg.req_timeout = Duration::from_millis(50);
  let timeout = Duration::from_millis(2000);
  cluster_complete(fail_map, clr_cfg, hbr_cfg, timeout, 40_000);
}

#[test]
fn cluster_test_with_failures() {
  let mut fail_map = FailureConfigMap::default();
  fail_map.cluster_wide.drop_prob = 0.5;
  fail_map.cluster_wide.delay = Some((Duration::from_millis(20), Duration::from_millis(50)));
  let mut clr_cfg = ClusterConfig::default();
  clr_cfg.vnodes = 1;
  clr_cfg.num_pings = 20;
  clr_cfg.ping_timeout = Duration::from_millis(200);
  let mut hbr_cfg = HBRConfig::default();
  hbr_cfg.req_tries = 1;
  hbr_cfg.req_timeout = Duration::from_millis(200);
  let timeout = Duration::from_millis(10_000);
  cluster_complete(fail_map, clr_cfg, hbr_cfg, timeout, 40_100);
}

fn cluster_cyclic(
  fail_map: FailureConfigMap,
  clr_cfg: ClusterConfig,
  hbr_cfg: HBRConfig,
  timeout: Duration,
  port: u16,
) {
  let ports = ((port + 1)..(port + 10)).collect::<Vec<_>>();
  let events = vec![
    Spawn(port + 1, ports.clone()),
    Spawn(port + 2, ports.clone()),
    Spawn(port + 3, ports.clone()),
    Spawn(port + 4, ports.clone()),
    Spawn(port + 5, ports.clone()),
    WaitForConvergence,
    Spawn(port + 6, ports.clone()),
    Spawn(port + 7, ports.clone()),
    Spawn(port + 8, ports.clone()),
    Spawn(port + 9, ports.clone()),
    Spawn(port + 10, ports.clone()),
    WaitForConvergence,
    Kill(port + 1),
    WaitForConvergence,
    Kill(port + 2),
    Kill(port + 3),
    Kill(port + 4),
    WaitForConvergence,
    Kill(port + 5),
    Kill(port + 6),
    Kill(port + 7),
    Kill(port + 8),
    Kill(port + 9),
    WaitForConvergence,
    Done,
  ];
  run_cluster_test(events, fail_map, clr_cfg, hbr_cfg, timeout, port);
}

#[test]
fn cluster_test_cyclic_perfect() {
  let fail_map = FailureConfigMap::default();
  let mut clr_cfg = ClusterConfig::default();
  clr_cfg.ping_timeout = Duration::from_millis(50);
  clr_cfg.vnodes = 1;
  let mut hbr_cfg = HBRConfig::default();
  hbr_cfg.req_tries = 1;
  hbr_cfg.req_timeout = Duration::from_millis(50);
  let timeout = Duration::from_millis(2000);
  cluster_cyclic(fail_map, clr_cfg, hbr_cfg, timeout, 40_200);
}

#[test]
fn cluster_test_cyclic_failures() {
  let mut fail_map = FailureConfigMap::default();
  fail_map.cluster_wide.drop_prob = 0.5;
  fail_map.cluster_wide.delay = Some((Duration::from_millis(20), Duration::from_millis(50)));
  let mut clr_cfg = ClusterConfig::default();
  clr_cfg.vnodes = 1;
  clr_cfg.num_pings = 20;
  clr_cfg.ping_timeout = Duration::from_millis(200);
  let mut hbr_cfg = HBRConfig::default();
  hbr_cfg.req_tries = 1;
  hbr_cfg.req_timeout = Duration::from_millis(200);
  let timeout = Duration::from_millis(10_000);
  cluster_cyclic(fail_map, clr_cfg, hbr_cfg, timeout, 40_300);
}
