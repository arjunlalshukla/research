use crate as aurum;
use crate::core::{
  deserialize, Actor, ActorContext, ActorName, Destination, DestinationUntyped, MessageBuilder,
  UnifiedBounds,
};
use async_trait::async_trait;
use aurum_macros::AurumInterface;
use std::collections::{hash_map::Entry, HashMap};
use tokio::sync::oneshot::Sender;

pub type SerializedRecvr<U> = Box<dyn Fn(U, MessageBuilder) -> bool + Send>;

#[derive(AurumInterface)]
#[aurum(local)]
pub enum RegistryMsg<U: UnifiedBounds> {
  Forward(MessageBuilder),
  Register(ActorName<U>, SerializedRecvr<U>, Sender<()>),
  Deregister(ActorName<U>),
}

pub struct Registry<U: UnifiedBounds> {
  pub register: HashMap<ActorName<U>, SerializedRecvr<U>>,
}
impl<U: UnifiedBounds> Registry<U> {
  pub fn new() -> Registry<U> {
    Registry {
      register: HashMap::new(),
    }
  }
}
#[async_trait]
impl<U: UnifiedBounds> Actor<U, RegistryMsg<U>> for Registry<U> {
  async fn recv(
    &mut self,
    ctx: &ActorContext<U, RegistryMsg<U>>,
    msg: RegistryMsg<U>,
  ) {
    match msg {
      RegistryMsg::Forward(msg_builder) => {
        let _packets = msg_builder.max_seq_num;
        let DestinationUntyped {
          name, interface
        } = deserialize::<DestinationUntyped<U>>(msg_builder.dest()).unwrap();
        if let Some(recvr) = self.register.get(&name) {
          if !recvr(interface, msg_builder) {
            self.register.remove(&name);
            println!("Forward message to {:?} failed, removing actor", name);
          } else {
            //println!("Forwarded {} packets to {:?}", packets, name);
          }
        } else {
          println!(
            "{}: cannot send to {:?}, not in register",
            ctx.node.socket().udp,
            name
          );
        }
      }
      RegistryMsg::Register(name, channel, confirmation) => {
        match self.register.entry(name) {
          Entry::Occupied(o) => {
            println!(
              "{}: Registry failed - already registered: {:?}",
              ctx.node.socket().udp,
              o.key()
            );
          }
          Entry::Vacant(v) => {
            if let Err(_) = confirmation.send(()) {
              println!("Register confirmation failed: {:?}", v.key());
            } else {
              //println!("{}: Adding actor to registry: {:?}", ctx.node.socket().udp, v.key());
              v.insert(channel);
            }
          }
        }
      }
      RegistryMsg::Deregister(name) => {
        //println!("Removing actor from registry: {:?}", name);
        self.register.remove(&name);
      }
    }
  }
}
