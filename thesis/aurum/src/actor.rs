use std::marker::PhantomData;
use std::fmt::Debug;
use std::sync::Arc;
use std::net::IpAddr;
use crate::unify::Case;
use crossbeam::{channel::Sender, thread::ScopedThreadBuilder};
use serde::{Serialize, Deserialize};
use serde::de::DeserializeOwned;

type LocalRef<T> = Arc<dyn Fn(T) -> bool>;

pub trait Actor<Unified: Clone + Case<Msg>, Msg> {
  fn pre_start(&mut self) {}
  fn recv(&mut self, ctx: ActorContext<Unified, Msg>, msg: Msg);
  fn post_stop(&mut self) {}
}

pub struct ActorContext<Unified: Case<Specific> + Clone, Specific> {
  tx: Sender<Specific>,
  address: Address<Unified>
}
impl<Unified, Specific> ActorContext<Unified, Specific>
 where Unified: Case<Specific> + Clone {
  fn local_ref<T>(&self) -> LocalRef<T> where Specific: From<T> + 'static {
    let sender = self.tx.clone();
    Arc::new(move |x: T| sender.send(Specific::from(x)).is_ok())
  }

  fn interface<T>(&self) -> ActorRef<Unified, T> where
   Unified: Case<T>,
   T: Serialize + DeserializeOwned,
   Specific: HasInterface<T> + From<T> + 'static {
    ActorRef::new(
      self.address.clone(),
      <Unified as Case<T>>::VARIANT,
      Some(self.local_ref::<T>()))
  }
}

pub enum DeserializeError<Unified> {
  IncompatibleInterface(Unified),
  Other(Unified)
}

pub trait HasInterface<T> {}

pub trait SpecificInterface<Unified> where 
 Self: Serialize + DeserializeOwned + Sized {
  fn deserialize_as(interface: Unified, bytes: Vec<u8>) -> 
    Result<Self, DeserializeError<Unified>>;
}

pub fn serialize<T>(item: T) -> Option<Vec<u8>>
 where T: Serialize + DeserializeOwned {
  serde_json::to_vec(&item).ok()
}

pub fn deserialize<T>(bytes: Vec<u8>) -> Option<T>
 where T: Serialize + DeserializeOwned {
  serde_json::from_slice(bytes.as_slice()).ok()
}

#[derive(Clone, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
pub enum Host { DNS(String), IP(IpAddr) }

#[derive(Clone, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
pub struct Node { host: Host, udp: u16, tcp: u16 }
impl Node {
  pub fn new(host: Host, udp: u16, tcp: u16) -> Node {
    Node {host: host, udp: udp, tcp: tcp}
  }
}

#[derive(Clone, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
pub struct Address<T: Clone> { node: Node, domain: T, name: String }
impl<T> Address<T> where T: Clone {
  pub fn new(node: Node, domain: T, name: String) -> Address<T> {
    Address { node: node, domain: domain, name: name }
  }
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(bound = "Unified: Case<Specific> + Serialize + DeserializeOwned")]
pub struct ActorRef<Unified, Specific> where Unified: Clone,
 Specific: Serialize + DeserializeOwned {
  addr: Address<Unified>,
  interface: Unified,
  #[serde(skip)] #[serde(default)]
  local: Option<LocalRef<Specific>>
}
impl<Unified, Specific> ActorRef<Unified, Specific> where 
 Unified: Clone + Case<Specific> + Serialize + DeserializeOwned,
 Specific: Serialize + DeserializeOwned {
  pub fn new(
    addr: Address<Unified>,
    interface: Unified,
    local: Option<LocalRef<Specific>>
  ) -> ActorRef<Unified, Specific> {
    ActorRef {addr: addr, interface: interface, local: local}
  }

  pub fn send(msg: Specific) {}
}
impl<Unified, Specific> Debug for ActorRef<Unified, Specific> where 
 Unified: Clone + Case<Specific> + Serialize + DeserializeOwned + Debug,
 Specific: Serialize + DeserializeOwned{
  fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
      f.debug_struct("ActorRef")
       .field("Unified", &std::any::type_name::<Unified>())
       .field("Specific", &std::any::type_name::<Specific>())
       .field("addr", &self.addr)
       .field("interface", &self.interface)
       .finish()
  }
}