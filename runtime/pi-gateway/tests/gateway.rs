use std::sync::Arc;
use std::time::Duration;

use clap::Parser;
use futures_util::{SinkExt, StreamExt};
use openhouse_pi_runtime::api;
use openhouse_pi_runtime::state::AppState;
use openhouse_pi_runtime::{Args, Config};
use reqwest::StatusCode;
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

const TOKEN: &str = "test-token-with-at-least-thirty-two-characters";

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn leases_isolate_sessions_and_frames_are_transparent() {
    let temporary = tempfile::tempdir().expect("tempdir");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let address = listener.local_addr().expect("address");
    let fake_pi = env!("CARGO_BIN_EXE_fake-pi");
    let args = Args::try_parse_from([
        "openhouse-pi-runtime",
        "--listen",
        &address.to_string(),
        "--pi-bin",
        fake_pi,
        "--sessions-dir",
        temporary
            .path()
            .join("sessions")
            .to_str()
            .expect("sessions"),
        "--state-dir",
        temporary.path().join("state").to_str().expect("state"),
        "--pi-working-dir",
        temporary.path().to_str().expect("cwd"),
        "--token",
        TOKEN,
        "--disconnect-grace-secs",
        "5",
        "--idle-timeout-secs",
        "30",
    ])
    .expect("args");
    let state = AppState::new(Config::from_args(args).expect("config")).expect("state");
    state.start_background_tasks();
    let server_state = Arc::clone(&state);
    let server = tokio::spawn(async move {
        axum::serve(listener, api::router(server_state))
            .await
            .expect("server");
    });

    let http = reqwest::Client::new();
    let base = format!("http://{address}");
    let unauthorized = http
        .get(format!("{base}/admin/v1/health"))
        .send()
        .await
        .expect("unauthorized response");
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);

    for session_id in ["chat_a", "chat_b"] {
        let created = authorized(
            http.post(format!("{base}/admin/v1/sessions"))
                .json(&json!({"sessionId": session_id, "cwd": temporary.path()})),
        )
        .send()
        .await
        .expect("create session");
        assert_eq!(created.status(), StatusCode::CREATED);
    }

    let lease_a = acquire(&http, &base, "chat_a", "native", false).await;
    let conflict = authorized(
        http.post(format!("{base}/admin/v1/leases"))
            .json(&json!({"sessionId":"chat_a", "clientId":"web", "takeover":false})),
    )
    .send()
    .await
    .expect("conflict");
    assert_eq!(conflict.status(), StatusCode::CONFLICT);
    let body: Value = conflict.json().await.expect("conflict json");
    assert_eq!(body["error"]["code"], "lease_conflict");

    let lease_b = acquire(&http, &base, "chat_b", "web", false).await;
    let mut ws_a = websocket(address, lease_a["leaseId"].as_str().expect("lease id")).await;
    let mut ws_b = websocket(address, lease_b["leaseId"].as_str().expect("lease id")).await;
    let frame_a = r#"{"id":"a1","type":"get_state"}"#;
    let frame_b = r#"{"id":"b1","type":"get_messages"}"#;
    ws_a.send(Message::Text(frame_a.into()))
        .await
        .expect("send a");
    ws_b.send(Message::Text(frame_b.into()))
        .await
        .expect("send b");
    assert_eq!(next_text(&mut ws_a).await, frame_a);
    assert_eq!(next_text(&mut ws_b).await, frame_b);

    let takeover = acquire(&http, &base, "chat_a", "web", true).await;
    let closed = tokio::time::timeout(Duration::from_secs(2), ws_a.next())
        .await
        .expect("old websocket closes");
    assert!(matches!(closed, Some(Ok(Message::Close(_))) | None));
    let mut replacement = websocket(
        address,
        takeover["leaseId"].as_str().expect("takeover lease"),
    )
    .await;
    let replacement_frame = r#"{"id":"a2","type":"get_state"}"#;
    replacement
        .send(Message::Text(replacement_frame.into()))
        .await
        .expect("replacement send");
    assert_eq!(next_text(&mut replacement).await, replacement_frame);

    replacement
        .send(Message::Text(r#"{"type":"fake_exit"}"#.into()))
        .await
        .expect("exit fake Pi");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let sessions: Value = authorized(http.get(format!("{base}/admin/v1/sessions")))
        .send()
        .await
        .expect("sessions")
        .json()
        .await
        .expect("sessions json");
    let chat_a = sessions["managed"]
        .as_array()
        .expect("managed")
        .iter()
        .find(|session| session["sessionId"] == "chat_a")
        .expect("chat a");
    assert_eq!(chat_a["process"]["state"], "exited");
    assert_eq!(chat_a["process"]["lastExit"]["exitCode"], 17);

    state.shutdown().await;
    server.abort();
}

fn authorized(builder: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
    builder.bearer_auth(TOKEN)
}

async fn acquire(
    http: &reqwest::Client,
    base: &str,
    session_id: &str,
    client_id: &str,
    takeover: bool,
) -> Value {
    authorized(http.post(format!("{base}/admin/v1/leases")).json(&json!({
        "sessionId": session_id,
        "clientId": client_id,
        "takeover": takeover,
    })))
    .send()
    .await
    .expect("lease")
    .error_for_status()
    .expect("lease status")
    .json()
    .await
    .expect("lease json")
}

async fn websocket(
    address: std::net::SocketAddr,
    lease_id: &str,
) -> tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>> {
    let mut request = format!("ws://{address}/ws/rpc/{lease_id}")
        .into_client_request()
        .expect("websocket request");
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {TOKEN}").parse().expect("auth header"),
    );
    connect_async(request).await.expect("connect websocket").0
}

async fn next_text(
    websocket: &mut tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
) -> String {
    loop {
        match websocket
            .next()
            .await
            .expect("websocket open")
            .expect("message")
        {
            Message::Text(text) => return text.to_string(),
            Message::Ping(_) | Message::Pong(_) => {}
            other => panic!("unexpected websocket message: {other:?}"),
        }
    }
}
