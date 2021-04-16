#![allow(unused_imports, dead_code, unused_variables)]

use crate as aurum;
use crate::cluster::{
  ClusterMsg, IntervalStorage, IntraClusterMsg, Member, NodeState,
  UnifiedBounds, FAILURE_CONFIG, FAILURE_MODE,
};
use crate::core::{ActorContext, Case, Destination, LocalRef, TimeoutActor};
use crate::{udp_select, AurumInterface};
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use HeartbeatReceiverMsg::*;

#[derive(Clone)]
pub struct HBRConfig {
  pub phi: f64,
  pub capacity: usize,
  pub times: usize,
  pub req_tries: usize,
  pub req_timeout: Duration,
}
impl Default for HBRConfig {
  fn default() -> Self {
    HBRConfig {
      phi: 0.995,
      capacity: 10,
      times: 3,
      req_tries: 3,
      req_timeout: Duration::from_millis(100),
    }
  }
}

#[derive(AurumInterface, Serialize, Deserialize)]
pub enum HeartbeatReceiverMsg {
  Heartbeat(Duration, u32),
}

pub(crate) enum HBRState {
  Initial(usize),
  Receiving(IntervalStorage, u32),
  Downed,
}

pub(crate) struct HeartbeatReceiver<U>
where
  U: UnifiedBounds + Case<HeartbeatReceiverMsg>,
{
  supervisor: LocalRef<ClusterMsg<U>>,
  member: Arc<Member>,
  clr_dest: Destination<U, IntraClusterMsg<U>>,
  charge: Arc<Member>,
  req: IntraClusterMsg<U>,
  state: HBRState,
  config: HBRConfig,
}
impl<U> HeartbeatReceiver<U>
where
  U: UnifiedBounds + Case<HeartbeatReceiverMsg>,
{
  pub fn from_clr(clr: &str, id: u64) -> String {
    format!("{}-{}", clr, id)
  }

  pub fn spawn(
    ctx: &ActorContext<U, ClusterMsg<U>>,
    common: &NodeState<U>,
    charge: Arc<Member>,
  ) -> LocalRef<HeartbeatReceiverMsg> {
    let cid = charge.id;
    ctx
      .node
      .spawn_timeout(
        HeartbeatReceiver {
          supervisor: ctx.local_interface(),
          member: common.member.clone(),
          charge: charge,
          clr_dest: common.clr_dest.clone(),
          req: IntraClusterMsg::ReqHeartbeat(common.member.clone(), cid),
          state: HBRState::Initial(common.hbr_config.req_tries),
          config: common.hbr_config.clone(),
        },
        Self::from_clr(common.clr_dest.name.name.as_str(), cid),
        true,
        common.hbr_config.req_timeout,
      )
      .local()
      .clone()
      .unwrap()
  }

  async fn send_req(&self) {
    udp_select!(
      FAILURE_MODE,
      FAILURE_CONFIG,
      &self.charge.socket,
      &self.clr_dest,
      &self.req
    );
  }
}
#[async_trait]
impl<U> TimeoutActor<U, HeartbeatReceiverMsg> for HeartbeatReceiver<U>
where
  U: UnifiedBounds + Case<HeartbeatReceiverMsg>,
{
  async fn pre_start(
    &mut self,
    _: &ActorContext<U, HeartbeatReceiverMsg>,
  ) -> Option<Duration> {
    println!(
      "{}: started HBR for {}-{}",
      self.member.socket.udp, self.charge.socket.udp, self.charge.id
    );
    self.send_req().await;
    None
  }

  async fn recv(
    &mut self,
    _: &ActorContext<U, HeartbeatReceiverMsg>,
    msg: HeartbeatReceiverMsg,
  ) -> Option<Duration> {
    let state = match &mut self.state {
      HBRState::Initial(_) => match msg {
        Heartbeat(dur, cnt) => {
          println!(
            "{}: new heartbeat interval from {}: {:?} ms",
            self.member.socket.udp,
            self.charge.socket.udp,
            dur.as_millis()
          );
          let is = IntervalStorage::new(
            self.config.capacity,
            dur,
            self.config.times,
            None,
          );
          let new_dur = Some(is.duration_phi(self.config.phi));
          let new_state = Some(HBRState::Receiving(is, cnt));
          (new_dur, new_state)
        }
      },
      HBRState::Receiving(storage, cnt) => match msg {
        Heartbeat(new_dur, new_cnt) => {
          if new_cnt > *cnt {
            /*
            println!(
              "{}: new heartbeat interval from {}: {:?} ms",
              self.member.socket.udp,
              self.charge.socket.udp,
              new_dur.as_millis()
            );
            */
            *storage = IntervalStorage::new(
              self.config.capacity,
              new_dur,
              self.config.times,
              None,
            );
          } else {
            storage.push();
          }
          let new_to = storage.duration_phi(self.config.phi);
          /*
          println!(
            "{}: got heartbeat from {}; new timeout: {:?} ms, stdev: {}, mean: {}",
            self.member.socket.udp,
            self.charge.socket.udp,
            new_to.as_millis(),
            storage.stdev(),
            storage.mean()
          );
          */
          (Some(new_to), None)
        }
      },
      HBRState::Downed => (Some(Duration::from_secs(u32::MAX as u64)), None),
    };
    state.1.into_iter().for_each(|s| self.state = s);
    state.0
  }

  async fn timeout(
    &mut self,
    _: &crate::core::ActorContext<U, HeartbeatReceiverMsg>,
  ) -> Option<Duration> {
    let state = match &mut self.state {
      HBRState::Initial(0) => {
        println!(
          "{}: DOWNED charge {}; after timeout: {:?} ms",
          self.member.socket.udp,
          self.charge.socket.udp,
          self.config.req_timeout.as_millis()
        );
        self.supervisor.send(ClusterMsg::Downed(self.charge.clone()));
        (Some(Duration::from_secs(u32::MAX as u64)), Some(HBRState::Downed))
      } 
      HBRState::Receiving(storage, _) => {
        println!(
          "{}: DOWNED charge {}; after timeout: {:?} ms, stdev: {}, mean: {}",
          self.member.socket.udp,
          self.charge.socket.udp,
          storage.duration_phi(self.config.phi).as_millis(),
          storage.stdev(),
          storage.mean()
        );
        self.supervisor.send(ClusterMsg::Downed(self.charge.clone()));
        (Some(Duration::from_secs(u32::MAX as u64)), Some(HBRState::Downed))
      }
      HBRState::Initial(ref mut reqs_left) => {
        *reqs_left -= 1;
        self.send_req().await;
        (Some(self.config.req_timeout), None)
      }
      HBRState::Downed => (Some(Duration::from_secs(u32::MAX as u64)), None)
    };
    state.1.into_iter().for_each(|s| self.state = s);
    state.0
  }

  async fn post_stop(
    &mut self,
    _: &ActorContext<U, HeartbeatReceiverMsg>,
  ) -> Option<Duration> {
    println!(
      "{}: killed HBR for {}-{}",
      self.member.socket.udp, self.charge.socket.udp, self.charge.id
    );
    None
  }
}
