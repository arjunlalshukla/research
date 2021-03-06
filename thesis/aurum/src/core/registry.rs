use crate as aurum;
use crate::core::{
  deserialize, Actor, ActorContext, ActorName, Destination, MessageBuilder,
  UnifiedBounds,
};
use async_trait::async_trait;
use aurum_macros::AurumInterface;
use std::collections::HashMap;
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
    _ctx: &ActorContext<U, RegistryMsg<U>>,
    msg: RegistryMsg<U>,
  ) {
    match msg {
      RegistryMsg::Forward(msg_builder) => {
        let Destination { name, interface } =
          deserialize::<Destination<U>>(msg_builder.dest()).unwrap();
        if let Some(recvr) = self.register.get(&name) {
          if !recvr(interface, msg_builder) {
            self.register.remove(&name);
            println!("Forward message to {:?} failed, removing actor", name);
          } else {
            //println!("Forwarded message to {:?}", name);
          }
        } else {
          println!("Cannot send to {:?}, not in register", name);
        }
      }
      RegistryMsg::Register(name, channel, confirmation) => {
        println!("Adding actor to registry: {:?}", name);
        self.register.insert(name.clone(), channel);
        if let Err(_) = confirmation.send(()) {
          println!("Could not send confirmation, removing from registry");
          self.register.remove(&name);
        }
      }
      RegistryMsg::Deregister(name) => {
        println!("Removing actor from registry: {:?}", name);
        self.register.remove(&name);
      }
    }
  }
}
