//! Plays real games against a running server over WebSockets: a race between two players and
//! guest races with host settings and replacement players.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use proto::{ClientMsg, ServerMsg, Visibility};
use sim::car::Input;
use sim::room::Phase;
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};

type Ws = WebSocketStream<MaybeTlsStream<TcpStream>>;

async fn start_server() -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = server::router(server::Hub::new());
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("ws://{addr}/")
}

struct Client {
    ws: Ws,
    seq: u32,
}

impl Client {
    async fn connect(url: &str) -> Client {
        let (ws, _) = tokio_tungstenite::connect_async(url).await.unwrap();
        let mut c = Client { ws, seq: 0 };
        c.send(ClientMsg::Hello { version: proto::VERSION }).await;
        assert!(matches!(c.recv().await, ServerMsg::Welcome { .. }));
        c
    }

    async fn send(&mut self, msg: ClientMsg) {
        self.ws.send(Message::Binary(proto::encode(&msg).into())).await.unwrap();
    }

    async fn input(&mut self, input: Input) {
        self.seq += 1;
        self.send(ClientMsg::Inputs { last: self.seq, inputs: vec![input.pack()] }).await;
    }

    async fn recv(&mut self) -> ServerMsg {
        loop {
            let msg = tokio::time::timeout(Duration::from_secs(10), self.ws.next()).await.expect("server went quiet");
            if let Message::Binary(b) = msg.unwrap().unwrap() {
                return proto::decode_server(&b).expect("a valid message");
            }
        }
    }

    /// waits for a message the test cares about, skipping the rest
    async fn until<T>(&mut self, mut pick: impl FnMut(&ServerMsg) -> Option<T>) -> T {
        loop {
            let msg = self.recv().await;
            if let Some(t) = pick(&msg) {
                return t;
            }
        }
    }
}

#[tokio::test]
async fn two_players_race() {
    let url = start_server().await;
    let mut a = Client::connect(&url).await;
    let mut b = Client::connect(&url).await;

    a.send(ClientMsg::Create { visibility: Visibility::Public, map: "tokyo".into(), car: 4 }).await;
    let (code, a_slot) = a.until(|m| match m {
        ServerMsg::Joined { code, slot, map, .. } if map == "tokyo" => Some((code.clone(), *slot)),
        _ => None,
    }).await;

    b.send(ClientMsg::ListRooms).await;
    let rooms = b.until(|m| match m { ServerMsg::Rooms(r) => Some(r.clone()), _ => None }).await;
    assert!(rooms.iter().any(|r| r.code == code && r.players == 1));
    b.send(ClientMsg::Join { code: code.to_lowercase(), car: 13 }).await;
    b.until(|m| matches!(m, ServerMsg::Joined { .. }).then_some(())).await;
    let lobby = a.until(|m| match m { ServerMsg::Lobby(l) if l.players.len() == 2 => Some(l.clone()), _ => None }).await;
    assert_eq!(lobby.host, Some(a_slot));
    assert_eq!(lobby.players[1].name, "Player 2");
    assert_eq!(lobby.settings.cpu_count, 0);
    a.send(ClientMsg::Ready(true)).await;
    a.until(|m| match m { ServerMsg::Lobby(l) if l.players.iter().any(|p| p.slot == a_slot && p.ready) => Some(()), _ => None }).await;
    a.send(ClientMsg::Settings(sim::room::Settings { cpu_count: 1, ..Default::default() })).await;
    a.until(|m| match m { ServerMsg::Lobby(l) if l.settings.cpu_count == 1 => {
        assert!(l.players.iter().all(|p| !p.ready)); Some(())
    }, _ => None }).await;

    // the host sets 2 laps; a guest can't change them
    a.send(ClientMsg::Settings(sim::room::Settings { laps: 2, difficulty: 2, cpu_count: 3 })).await;
    b.until(|m| match m { ServerMsg::Lobby(l) if l.settings.cpu_count == 3 => Some(()), _ => None }).await;
    b.send(ClientMsg::Settings(sim::room::Settings { laps: 5, difficulty: 0, cpu_count: 0 })).await;
    b.send(ClientMsg::Ready(false)).await;
    b.until(|m| match m { ServerMsg::Lobby(l) if l.settings.cpu_count == 3 => {
        assert_eq!(l.settings.laps, 2); Some(())
    }, _ => None }).await;
    // Remove the CPUs before measuring human input delivery: random AI/items should
    // not decide whether this network transport test passes.
    a.send(ClientMsg::Settings(sim::room::Settings { laps: 2, difficulty: 2, cpu_count: 0 })).await;
    b.until(|m| match m { ServerMsg::Lobby(l) if l.settings.cpu_count == 0 => Some(()), _ => None }).await;
    a.send(ClientMsg::Ready(true)).await;
    b.send(ClientMsg::Ready(true)).await;
    let settings = a.until(|m| match m { ServerMsg::Lobby(l) if l.players.iter().all(|p| p.ready) => Some(l.settings), _ => None }).await;
    assert_eq!(settings.laps, 2);
    assert_eq!(settings.cpu_count, 0);

    // wait for GO, then hold the gas
    a.until(|m| match m { ServerMsg::Race { snapshot, .. } if snapshot.phase == Phase::Racing => Some(()), _ => None }).await;
    let start = a.until(|m| match m { ServerMsg::Race { snapshot, .. } => snapshot.cars.iter().find(|c| c.slot == a_slot).map(|c| c.car.pos), _ => None }).await;
    for _ in 0..90 {
        a.input(Input { gas: true, ..Input::default() }).await;
        tokio::time::sleep(Duration::from_millis(16)).await;
    }
    let (pos, ack) = a.until(|m| match m {
        ServerMsg::Race { snapshot, ack, .. } if *ack > 60 => snapshot.cars.iter().find(|c| c.slot == a_slot).map(|c| (c.car.pos, *ack)),
        _ => None,
    }).await;
    assert!(pos.dist(start) > 50.0, "the car drove {} px", pos.dist(start));
    assert!(ack <= a.seq);

    // a player leaving mid-race disappears from the race
    b.send(ClientMsg::Leave).await;
    a.until(|m| match m { ServerMsg::Race { snapshot, .. } if snapshot.cars.len() == 1 => Some(()), _ => None }).await;
}

#[tokio::test]
async fn unknown_codes_and_old_clients_are_turned_away() {
    let url = start_server().await;
    let mut a = Client::connect(&url).await;
    a.send(ClientMsg::Join { code: "ZZZZ".into(), car: 1 }).await;
    assert!(matches!(a.recv().await, ServerMsg::Error(e) if e.contains("ZZZZ")));
    a.send(ClientMsg::Hello { version: 1 }).await;
    assert!(matches!(a.recv().await, ServerMsg::Error(e) if e.contains("reload")));
}

#[tokio::test]
async fn private_guest_rooms_stay_unlisted_and_transfer_host() {
    let url = start_server().await;
    let mut a = Client::connect(&url).await;
    let mut b = Client::connect(&url).await;
    a.send(ClientMsg::Create { visibility: Visibility::Private, map: "classic".into(), car: 4 }).await;
    let code = a.until(|m| match m {
        ServerMsg::Joined { code, visibility: Visibility::Private, .. } => Some(code.clone()),
        _ => None,
    }).await;
    b.send(ClientMsg::ListRooms).await;
    let rooms = b.until(|m| match m { ServerMsg::Rooms(r) => Some(r.clone()), _ => None }).await;
    assert!(rooms.iter().all(|r| r.code != code));
    b.send(ClientMsg::Join { code, car: 1 }).await;
    let slot = b.until(|m| match m { ServerMsg::Joined { slot, .. } => Some(*slot), _ => None }).await;
    let lobby = b.until(|m| match m { ServerMsg::Lobby(l) if l.players.len() == 2 => Some(l.clone()), _ => None }).await;
    assert_eq!(lobby.players[0].name, "Player 1");
    assert_eq!(lobby.players[1].name, "Player 2");
    a.send(ClientMsg::Leave).await;
    b.until(|m| match m { ServerMsg::Lobby(l) if l.host == Some(slot) => Some(()), _ => None }).await;
    b.send(ClientMsg::Settings(sim::room::Settings { laps: 3, difficulty: 2, cpu_count: 3 })).await;
    b.until(|m| match m { ServerMsg::Lobby(l) if l.settings.laps == 3 => Some(()), _ => None }).await;
    b.send(ClientMsg::Ready(true)).await;
    b.until(|m| match m { ServerMsg::Race { snapshot, .. } if snapshot.phase == Phase::Flag => Some(()), _ => None }).await;
}
