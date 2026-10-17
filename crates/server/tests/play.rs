//! Plays real games against a running server over WebSockets: a race between two players and
//! a 1v1 soccer match with a rematch and a replacement player.

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
    let app = server::router(server::Hub::new(server::accounts::Accounts::from_env()));
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

    // the host sets 2 laps; a guest can't change them
    a.send(ClientMsg::Settings(sim::room::Settings { laps: 2, difficulty: 2 })).await;
    b.send(ClientMsg::Settings(sim::room::Settings { laps: 5, difficulty: 0 })).await;
    a.send(ClientMsg::Ready(true)).await;
    b.send(ClientMsg::Ready(true)).await;
    let settings = a.until(|m| match m { ServerMsg::Lobby(l) if l.players.iter().all(|p| p.ready) => Some(l.settings), _ => None }).await;
    assert_eq!(settings.laps, 2);

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
    a.until(|m| match m { ServerMsg::Race { snapshot, .. } if snapshot.cars.len() == 4 => Some(()), _ => None }).await;
}

#[tokio::test]
async fn unknown_codes_and_old_clients_are_turned_away() {
    let url = start_server().await;
    let mut a = Client::connect(&url).await;
    a.send(ClientMsg::Join { code: "ZZZZ".into(), car: 1 }).await;
    assert!(matches!(a.recv().await, ServerMsg::Error(e) if e.contains("ZZZZ")));
    a.send(ClientMsg::Ranked { car: 1 }).await;
    assert!(matches!(a.recv().await, ServerMsg::Error(e) if e.contains("Sign in")));
    a.send(ClientMsg::Hello { version: 0 }).await;
    assert!(matches!(a.recv().await, ServerMsg::Error(e) if e.contains("reload")));
}

#[tokio::test]
async fn soccer_match_with_rematch_and_replacement() {
    let url = start_server().await;
    let mut a = Client::connect(&url).await;
    let mut b = Client::connect(&url).await;
    a.send(ClientMsg::SoccerCreate { team_size: 1 }).await;
    let code = a.until(|m| match m { ServerMsg::SoccerJoined { code, slot: 0 } => Some(code.clone()), _ => None }).await;
    b.send(ClientMsg::SoccerList).await;
    let rooms = b.until(|m| match m { ServerMsg::SoccerRooms(r) => Some(r.clone()), _ => None }).await;
    assert!(rooms.iter().any(|r| r.code == code));
    b.send(ClientMsg::SoccerJoin { code: code.clone() }).await;
    b.until(|m| matches!(m, ServerMsg::SoccerJoined { slot: 1, .. }).then_some(())).await;

    // kickoff, then blue drives straight at the ball
    use sim::soccer::SoccerPhase as P;
    a.until(|m| match m { ServerMsg::Soccer { state, .. } if state.phase == P::Playing => Some(()), _ => None }).await;
    for _ in 0..240 {
        a.input(Input { gas: true, boost: true, ..Input::default() }).await;
        tokio::time::sleep(Duration::from_millis(16)).await;
    }
    let hit = b.until(|m| match m {
        ServerMsg::Soccer { state, host, .. } if state.ball.vx.abs() > 1.0 || state.blue > 0 => Some(*host),
        _ => None,
    });
    let host = tokio::time::timeout(Duration::from_secs(5), hit).await.expect("blue's car never reached the ball");
    assert_eq!(host, Some(0));

    // orange leaves: back to waiting, and a replacement can take the slot
    b.send(ClientMsg::Leave).await;
    a.until(|m| match m { ServerMsg::Soccer { state, .. } if state.phase == P::Waiting => Some(()), _ => None }).await;
    let mut c = Client::connect(&url).await;
    c.send(ClientMsg::SoccerJoin { code }).await;
    c.until(|m| matches!(m, ServerMsg::SoccerJoined { slot: 1, .. }).then_some(())).await;
    a.until(|m| match m { ServerMsg::Soccer { state, .. } if state.phase == P::Kickoff => Some(()), _ => None }).await;
}
