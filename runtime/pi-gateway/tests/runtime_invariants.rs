use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use axum::extract::{Path as AxumPath, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::post;
use axum::{Json, Router};
use clap::Parser;
use openhouse_pi_runtime::api;
use openhouse_pi_runtime::process::ProcessState;
use openhouse_pi_runtime::state::{AcquireLease, AppState, CreateSession, RegisterAndroidBridge};
use openhouse_pi_runtime::{Args, Config};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

const TOKEN: &str = "test-token-with-at-least-thirty-two-characters";

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn active_agent_is_not_reaped_and_new_session_path_survives_restart() {
    let temporary = tempfile::tempdir().expect("tempdir");
    let address = unused_address().await;
    let config = test_config(
        temporary.path(),
        address,
        env!("CARGO_BIN_EXE_fake-pi"),
        1,
        1,
    );
    let state = AppState::new(config.clone()).expect("state");
    state.start_background_tasks();
    let session = state
        .create_session(CreateSession {
            session_id: Some("persistent".into()),
            session_path: None,
            cwd: None,
        })
        .await
        .expect("create session");
    state
        .acquire_lease(AcquireLease {
            session_id: "persistent".into(),
            client_id: "native".into(),
            takeover: false,
        })
        .await
        .expect("lease");
    let mut output = session.process.subscribe_output();

    session
        .process
        .send_line(json!({"type":"fake_env"}).to_string())
        .await
        .expect("fake env");
    let environment: Value =
        serde_json::from_str(&output.recv().await.expect("env output")).expect("env json");
    assert_eq!(environment["bridgeTokenPresent"], false);
    assert_eq!(environment["runtimeBearerPresent"], false);
    assert!(environment["bridgeUrl"].as_str().is_some());
    assert!(environment["bridgeHelper"].as_str().is_some());

    session
        .process
        .send_line(json!({"type":"fake_agent_start"}).to_string())
        .await
        .expect("agent start");
    assert_eq!(
        serde_json::from_str::<Value>(&output.recv().await.expect("start event"))
            .expect("start json")["type"],
        "agent_start"
    );
    let registry_path = temporary.path().join("state/sessions.json");
    let registry = wait_for_registry_session_path(&registry_path).await;
    assert_eq!(registry[0]["sessionPath"], "fake-session.jsonl");
    assert_eq!(session.process.status().await.state, ProcessState::Running);
    // Shut down while the very first agent turn is still active. The persisted
    // session path must already be sufficient for a full gateway restart.
    state.shutdown().await;
    wait_for_state(&session, ProcessState::Exited).await;
    drop(state);

    let restarted = AppState::new(config).expect("restarted state");
    restarted.start_background_tasks();
    let adopted = restarted
        .create_session(CreateSession {
            session_id: Some("duplicate".into()),
            session_path: Some("fake-session.jsonl".into()),
            cwd: None,
        })
        .await
        .expect("adopt existing path");
    assert_eq!(adopted.id(), "persistent");
    assert_eq!(restarted.session_views().await.len(), 1);
    let resumed = restarted
        .get_session("persistent")
        .await
        .expect("persisted session");
    let mut resumed_output = resumed.process.subscribe_output();
    resumed.process.ensure_running().await.expect("restart Pi");
    resumed
        .process
        .send_line(json!({"type":"fake_args"}).to_string())
        .await
        .expect("fake args");
    let arguments: Value =
        serde_json::from_str(&resumed_output.recv().await.expect("fake args output"))
            .expect("args json");
    assert!(
        arguments["sessionPath"]
            .as_str()
            .is_some_and(|path| path.ends_with("fake-session.jsonl"))
    );

    resumed
        .process
        .send_line(json!({"type":"fake_agent_start"}).to_string())
        .await
        .expect("resumed agent start");
    assert_eq!(
        serde_json::from_str::<Value>(&resumed_output.recv().await.expect("resumed start"))
            .expect("resumed start json")["type"],
        "agent_start"
    );
    tokio::time::sleep(Duration::from_millis(5_500)).await;
    assert_eq!(resumed.process.status().await.state, ProcessState::Running);
    resumed
        .process
        .send_line(json!({"type":"fake_agent_end"}).to_string())
        .await
        .expect("resumed agent end");
    assert_eq!(
        serde_json::from_str::<Value>(&resumed_output.recv().await.expect("resumed end"))
            .expect("resumed end json")["type"],
        "agent_end"
    );
    wait_for_state(&resumed, ProcessState::Exited).await;
    restarted.shutdown().await;
}

#[tokio::test]
async fn bridge_proxy_keeps_token_out_of_pi_and_uses_latest_registration() {
    let temporary = tempfile::tempdir().expect("tempdir");
    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("gateway bind");
    let address = listener.local_addr().expect("gateway address");
    let config = test_config(
        temporary.path(),
        address,
        env!("CARGO_BIN_EXE_fake-pi"),
        5,
        30,
    );
    let state = AppState::new(config).expect("state");
    let server_state = Arc::clone(&state);
    let gateway = tokio::spawn(async move {
        axum::serve(listener, api::router(server_state))
            .await
            .expect("gateway server");
    });

    let (first_port, first_server) =
        upstream("first-token-value-with-thirty-two-chars", true).await;
    state
        .register_android_bridge(RegisterAndroidBridge {
            client_id: "native".into(),
            port: first_port,
            token: "first-token-value-with-thirty-two-chars".into(),
        })
        .await
        .expect("first registration");
    let proxy = format!(
        "http://{address}/internal/v1/android-bridge/{}",
        state.bridge_proxy_id()
    );
    let request = json!({"id":"call-1","arguments":{"operation":"read"}});
    let first: Value = reqwest::Client::new()
        .post(format!("{proxy}/v1/tools/clipboard"))
        .json(&request)
        .send()
        .await
        .expect("first proxy")
        .json()
        .await
        .expect("first json");
    assert_eq!(first["isError"], true);
    assert_eq!(first["error"]["message"], "first denied");

    let (second_port, second_server) =
        upstream("second-token-value-with-thirty-two-char", false).await;
    state
        .register_android_bridge(RegisterAndroidBridge {
            client_id: "native".into(),
            port: second_port,
            token: "second-token-value-with-thirty-two-char".into(),
        })
        .await
        .expect("second registration");
    let second: Value = reqwest::Client::new()
        .post(format!("{proxy}/v1/tools/notification"))
        .json(&json!({"id":"call-2","arguments":{"title":"t","text":"x"}}))
        .send()
        .await
        .expect("second proxy")
        .json()
        .await
        .expect("second json");
    assert_eq!(second["isError"], false);
    assert_eq!(second["content"]["source"], "second");

    state.shutdown().await;
    gateway.abort();
    first_server.abort();
    second_server.abort();
}

#[tokio::test]
async fn websocket_start_failure_rolls_back_connected_lease() {
    let temporary = tempfile::tempdir().expect("tempdir");
    let copied_pi = temporary.path().join("fake-pi");
    fs::copy(env!("CARGO_BIN_EXE_fake-pi"), &copied_pi).expect("copy fake Pi");
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let address = listener.local_addr().expect("address");
    let config = test_config(temporary.path(), address, &copied_pi, 5, 30);
    let state = AppState::new(config).expect("state");
    let lease = state
        .acquire_lease(AcquireLease {
            session_id: "rollback".into(),
            client_id: "native".into(),
            takeover: false,
        })
        .await
        .expect("lease");
    let session = state.get_session("rollback").await.expect("session");
    session.process.stop().await;
    wait_for_state(&session, ProcessState::Exited).await;
    fs::remove_file(&copied_pi).expect("remove fake Pi");

    let server_state = Arc::clone(&state);
    let server = tokio::spawn(async move {
        axum::serve(listener, api::router(server_state))
            .await
            .expect("server");
    });
    let mut request = format!("ws://{address}{}", lease.ws_path)
        .into_client_request()
        .expect("request");
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {TOKEN}").parse().expect("auth"),
    );
    let error = connect_async(request).await.expect_err("upgrade must fail");
    assert!(error.to_string().contains("500"));
    let view = state.session_views().await.remove(0);
    assert_eq!(view.connected_clients, 0);
    assert!(!view.lease.expect("lease view").connected);

    state.shutdown().await;
    server.abort();
}

fn test_config(
    root: &Path,
    listen: std::net::SocketAddr,
    pi_bin: impl AsRef<Path>,
    disconnect_grace: u64,
    idle_timeout: u64,
) -> Config {
    let extension = Path::new(env!("CARGO_MANIFEST_DIR")).join("../extensions/openhouse-tools");
    let strings = vec![
        "openhouse-pi-runtime".to_string(),
        "--listen".into(),
        listen.to_string(),
        "--pi-bin".into(),
        pi_bin.as_ref().display().to_string(),
        "--sessions-dir".into(),
        root.join("sessions").display().to_string(),
        "--state-dir".into(),
        root.join("state").display().to_string(),
        "--pi-working-dir".into(),
        root.display().to_string(),
        "--extension".into(),
        extension.display().to_string(),
        "--token".into(),
        TOKEN.into(),
        "--disconnect-grace-secs".into(),
        disconnect_grace.to_string(),
        "--idle-timeout-secs".into(),
        idle_timeout.to_string(),
    ];
    Config::from_args(Args::try_parse_from(strings).expect("args")).expect("config")
}

async fn unused_address() -> std::net::SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    listener.local_addr().expect("address")
}

async fn wait_for_state(
    session: &openhouse_pi_runtime::state::ManagedSession,
    expected: ProcessState,
) {
    tokio::time::timeout(Duration::from_secs(7), async {
        loop {
            if session.process.status().await.state == expected {
                break;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .expect("process state timeout");
}

async fn wait_for_registry_session_path(path: &Path) -> Value {
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            if let Ok(bytes) = fs::read(path)
                && let Ok(registry) = serde_json::from_slice::<Value>(&bytes)
                && registry[0]["sessionPath"].is_string()
            {
                break registry;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .expect("session path persistence timeout")
}

#[derive(Clone)]
struct UpstreamState {
    token: &'static str,
    fail: bool,
}

async fn upstream(token: &'static str, fail: bool) -> (u16, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("upstream bind");
    let port = listener.local_addr().expect("upstream address").port();
    let app = Router::new()
        .route("/v1/tools/{name}", post(upstream_tool))
        .with_state(UpstreamState { token, fail });
    let server = tokio::spawn(async move {
        axum::serve(listener, app).await.expect("upstream server");
    });
    (port, server)
}

async fn upstream_tool(
    State(state): State<UpstreamState>,
    AxumPath(name): AxumPath<String>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> (StatusCode, Json<Value>) {
    if headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        != Some(&format!("Bearer {}", state.token))
    {
        return (StatusCode::UNAUTHORIZED, Json(json!({"badAuth": true})));
    }
    if body.as_object().map(serde_json::Map::len) != Some(2)
        || !body["id"].is_string()
        || !body["arguments"].is_object()
    {
        return (StatusCode::BAD_REQUEST, Json(json!({"badBody": true})));
    }
    if state.fail {
        (
            StatusCode::OK,
            Json(json!({
                "callId": body["id"],
                "isError": true,
                "content": {"source":"first"},
                "error": {"code":"denied","message":"first denied","retryable":false}
            })),
        )
    } else {
        (
            StatusCode::OK,
            Json(json!({
                "callId": body["id"],
                "isError": false,
                "content": {"source":"second","tool":name}
            })),
        )
    }
}
