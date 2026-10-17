//! VAR Races online server: race and soccer rooms over WebSockets.
//!
//! Players connect to `/` with a WebSocket (binary frames, see the proto crate). A plain GET of
//! `/` or `/health` answers with a short status, for the host's health checks.

pub mod accounts;
mod conn;
mod hub;
mod inputs;
mod race;
mod soccer;

use std::sync::Arc;

use axum::extract::ws::rejection::WebSocketUpgradeRejection;
use axum::extract::{State, WebSocketUpgrade};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;

pub use crate::hub::Hub;

/// the server's routes
pub fn router(hub: Arc<Hub>) -> Router {
    Router::new().route("/", get(root)).route("/health", get(health)).with_state(hub)
}

async fn root(ws: Result<WebSocketUpgrade, WebSocketUpgradeRejection>, State(hub): State<Arc<Hub>>) -> Response {
    match ws {
        Ok(ws) => ws
            .max_message_size(proto::MAX_CLIENT_FRAME)
            .on_upgrade(move |socket| conn::serve(socket, hub))
            .into_response(),
        Err(_) => health(State(hub)).await.into_response(),
    }
}

async fn health(State(hub): State<Arc<Hub>>) -> String {
    let (races, soccer) = hub.counts();
    format!("VAR Races server: {races} races, {soccer} soccer matches\n")
}
