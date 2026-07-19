use std::sync::Arc;
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::ws::{CloseFrame, Message, WebSocket};
use axum::extract::{DefaultBodyLimit, Path, State, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use subtle::ConstantTimeEq;
use tokio::sync::broadcast;
use uuid::Uuid;

use crate::Error;
use crate::backup::{create_backup, list_disk_sessions, restore_backup};
use crate::config::SettingsPatch;
use crate::process::ProcessState;
use crate::state::{AcquireLease, AppState, CreateSession, ManagedSession, RegisterAndroidBridge};

pub fn router(state: Arc<AppState>) -> Router {
    let body_limit = usize::try_from(state.config.settings.max_backup_bytes())
        .unwrap_or(usize::MAX)
        .saturating_add(1024 * 1024);
    Router::new()
        .route("/admin/v1/health", get(health))
        .route("/admin/v1/config", get(get_config).patch(update_config))
        .route(
            "/admin/v1/sessions",
            get(list_sessions).post(create_session),
        )
        .route("/admin/v1/sessions/{session_id}", delete(delete_session))
        .route("/admin/v1/leases", get(list_leases).post(acquire_lease))
        .route("/admin/v1/leases/{lease_id}", delete(release_lease))
        .route("/admin/v1/backup", get(backup))
        .route("/admin/v1/restore", post(restore))
        .route(
            "/admin/v1/android-bridge",
            get(get_android_bridge)
                .post(register_android_bridge)
                .delete(unregister_android_bridge),
        )
        .route(
            "/internal/v1/android-bridge/{proxy_id}/v1/tools/{tool_name}",
            post(proxy_android_bridge),
        )
        .route("/ws/rpc/{lease_id}", get(websocket))
        .layer(DefaultBodyLimit::max(body_limit))
        .with_state(state)
}

#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    code: &'static str,
    message: String,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(json!({
                "error": {
                    "code": self.code,
                    "message": self.message,
                }
            })),
        )
            .into_response()
    }
}

impl From<Error> for ApiError {
    fn from(error: Error) -> Self {
        let (status, code) = match &error {
            Error::InvalidRequest(_) | Error::Json(_) | Error::Zip(_) => {
                (StatusCode::BAD_REQUEST, "invalid_request")
            }
            Error::NotFound(_) => (StatusCode::NOT_FOUND, "not_found"),
            Error::Conflict(message) if message == "lease_conflict" => {
                (StatusCode::CONFLICT, "lease_conflict")
            }
            Error::Conflict(_) => (StatusCode::CONFLICT, "conflict"),
            Error::Config(_) | Error::Io(_) | Error::Process(_) => {
                (StatusCode::INTERNAL_SERVER_ERROR, "runtime_error")
            }
        };
        Self {
            status,
            code,
            message: error.to_string(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct HealthResponse {
    status: &'static str,
    uptime_seconds: u64,
    managed_sessions: usize,
    running_sessions: usize,
    degraded_sessions: usize,
}

async fn health(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<Json<HealthResponse>, ApiError> {
    authorize(&state, &headers)?;
    let sessions = state.session_views().await;
    let running = sessions
        .iter()
        .filter(|session| session.process.state == ProcessState::Running)
        .count();
    let degraded = sessions
        .iter()
        .filter(|session| {
            matches!(
                session.process.state,
                ProcessState::Failed | ProcessState::Exited
            ) && session
                .process
                .last_exit
                .as_ref()
                .is_some_and(|exit| !exit.expected)
        })
        .count();
    Ok(Json(HealthResponse {
        status: if degraded == 0 { "ok" } else { "degraded" },
        uptime_seconds: crate::process::now_epoch_ms().saturating_sub(state.started_at_epoch_ms)
            / 1000,
        managed_sessions: sessions.len(),
        running_sessions: running,
        degraded_sessions: degraded,
    }))
}

async fn get_config(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<Json<impl Serialize>, ApiError> {
    authorize(&state, &headers)?;
    Ok(Json(state.config.public()))
}

async fn update_config(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(patch): Json<SettingsPatch>,
) -> std::result::Result<Json<impl Serialize>, ApiError> {
    authorize(&state, &headers)?;
    let snapshot = state.config.settings.apply(&patch)?;
    state.config.persist_settings(&snapshot)?;
    Ok(Json(state.config.public()))
}

async fn list_sessions(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<Json<Value>, ApiError> {
    authorize(&state, &headers)?;
    let root = state.config.sessions_dir.clone();
    let available = tokio::task::spawn_blocking(move || list_disk_sessions(&root))
        .await
        .map_err(|error| Error::Io(std::io::Error::other(error)))??;
    Ok(Json(json!({
        "managed": state.session_views().await,
        "available": available,
    })))
}

async fn create_session(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(request): Json<CreateSession>,
) -> std::result::Result<(StatusCode, Json<Value>), ApiError> {
    authorize(&state, &headers)?;
    let session = state.create_session(request).await?;
    Ok((
        StatusCode::CREATED,
        Json(json!({"sessionId": session.id()})),
    ))
}

async fn delete_session(
    State(state): State<Arc<AppState>>,
    Path(session_id): Path<String>,
    headers: HeaderMap,
) -> std::result::Result<StatusCode, ApiError> {
    authorize(&state, &headers)?;
    state.remove_session(&session_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_leases(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<Json<Value>, ApiError> {
    authorize(&state, &headers)?;
    let leases: Vec<_> = state
        .session_views()
        .await
        .into_iter()
        .filter_map(|session| {
            session.lease.map(|lease| {
                json!({
                    "sessionId": session.descriptor.session_id,
                    "leaseId": lease.lease_id,
                    "clientId": lease.client_id,
                    "connected": lease.connected,
                    "acquiredAtEpochMs": lease.acquired_at_epoch_ms,
                    "disconnectedAtEpochMs": lease.disconnected_at_epoch_ms,
                })
            })
        })
        .collect();
    Ok(Json(json!({"leases": leases})))
}

async fn acquire_lease(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(request): Json<AcquireLease>,
) -> std::result::Result<Json<impl Serialize>, ApiError> {
    authorize(&state, &headers)?;
    Ok(Json(state.acquire_lease(request).await?))
}

async fn release_lease(
    State(state): State<Arc<AppState>>,
    Path(lease_id): Path<Uuid>,
    headers: HeaderMap,
) -> std::result::Result<StatusCode, ApiError> {
    authorize(&state, &headers)?;
    state.release_lease(lease_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn backup(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<Response, ApiError> {
    authorize(&state, &headers)?;
    let root = state.config.sessions_dir.clone();
    let max = state.config.settings.max_backup_bytes();
    let bytes = tokio::task::spawn_blocking(move || create_backup(&root, max))
        .await
        .map_err(|error| Error::Io(std::io::Error::other(error)))??;
    Ok((
        [
            (header::CONTENT_TYPE, "application/zip"),
            (
                header::CONTENT_DISPOSITION,
                "attachment; filename=\"openhouse-pi-conversations-v1.zip\"",
            ),
        ],
        bytes,
    )
        .into_response())
}

async fn restore(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    bytes: Bytes,
) -> std::result::Result<Json<impl Serialize>, ApiError> {
    authorize(&state, &headers)?;
    let root = state.config.sessions_dir.clone();
    let max = state.config.settings.max_backup_bytes();
    let report = tokio::task::spawn_blocking(move || restore_backup(&root, &bytes, max))
        .await
        .map_err(|error| Error::Io(std::io::Error::other(error)))??;
    Ok(Json(report))
}

async fn get_android_bridge(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<Json<Value>, ApiError> {
    authorize(&state, &headers)?;
    Ok(Json(json!({"bridge": state.android_bridge_view().await})))
}

async fn register_android_bridge(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(request): Json<RegisterAndroidBridge>,
) -> std::result::Result<Json<impl Serialize>, ApiError> {
    authorize(&state, &headers)?;
    Ok(Json(state.register_android_bridge(request).await?))
}

async fn unregister_android_bridge(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> std::result::Result<StatusCode, ApiError> {
    authorize(&state, &headers)?;
    state.unregister_android_bridge().await;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct BridgeProxyRequest {
    id: String,
    arguments: serde_json::Map<String, Value>,
}

async fn proxy_android_bridge(
    State(state): State<Arc<AppState>>,
    Path((proxy_id, tool_name)): Path<(Uuid, String)>,
    Json(request): Json<BridgeProxyRequest>,
) -> Response {
    if proxy_id != state.bridge_proxy_id() || !is_android_tool(&tool_name) {
        return bridge_failure(
            StatusCode::NOT_FOUND,
            &request.id,
            "tool_not_found",
            "Android tool proxy was not found",
            false,
        );
    }
    let Ok((port, token)) = state.android_bridge_target().await else {
        return bridge_failure(
            StatusCode::SERVICE_UNAVAILABLE,
            &request.id,
            "bridge_unavailable",
            "Android Bridge is not registered",
            true,
        );
    };
    let upstream = format!("http://127.0.0.1:{port}/v1/tools/{tool_name}");
    match state
        .bridge_http
        .post(upstream)
        .bearer_auth(token.as_ref())
        .json(&request)
        .send()
        .await
    {
        Ok(response) => {
            let status = response.status();
            match response.bytes().await {
                Ok(body) => (
                    status,
                    [(header::CONTENT_TYPE, "application/json; charset=utf-8")],
                    body,
                )
                    .into_response(),
                Err(error) => bridge_failure(
                    StatusCode::BAD_GATEWAY,
                    &request.id,
                    "bridge_read_failed",
                    &format!("Failed to read Android Bridge response: {error}"),
                    true,
                ),
            }
        }
        Err(error) => bridge_failure(
            StatusCode::BAD_GATEWAY,
            &request.id,
            "bridge_request_failed",
            &format!("Android Bridge request failed: {error}"),
            true,
        ),
    }
}

fn is_android_tool(name: &str) -> bool {
    matches!(name, "clipboard" | "intent" | "share" | "notification")
}

fn bridge_failure(
    status: StatusCode,
    call_id: &str,
    code: &str,
    message: &str,
    retryable: bool,
) -> Response {
    (
        status,
        Json(json!({
            "callId": call_id,
            "isError": true,
            "content": {},
            "error": {
                "code": code,
                "message": message,
                "retryable": retryable,
            }
        })),
    )
        .into_response()
}

async fn websocket(
    State(state): State<Arc<AppState>>,
    Path(lease_id): Path<Uuid>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> std::result::Result<Response, ApiError> {
    authorize(&state, &headers)?;
    let session = state.find_lease(lease_id).await?;
    let generation = session.connect_lease(lease_id).await?;
    if let Err(error) = session.process.ensure_running().await {
        session.disconnect_lease(lease_id).await;
        return Err(error.into());
    }
    Ok(upgrade.on_upgrade(move |socket| websocket_loop(socket, session, lease_id, generation)))
}

enum SocketEvent {
    Client(Option<std::result::Result<Message, axum::Error>>),
    Pi(std::result::Result<String, broadcast::error::RecvError>),
    LeaseChanged,
    ProcessChanged,
    Ping,
}

async fn websocket_loop(
    socket: WebSocket,
    session: Arc<ManagedSession>,
    lease_id: Uuid,
    mut lease_generation: tokio::sync::watch::Receiver<u64>,
) {
    let captured_generation = *lease_generation.borrow();
    let mut output = session.process.subscribe_output();
    let mut status = session.process.subscribe_status();
    let (mut sender, mut receiver) = socket.split();
    let mut ping = tokio::time::interval(Duration::from_secs(20));
    ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        let event = tokio::select! {
            message = receiver.next() => SocketEvent::Client(message),
            line = output.recv() => SocketEvent::Pi(line),
            result = lease_generation.changed() => {
                let _ = result;
                SocketEvent::LeaseChanged
            },
            result = status.changed() => {
                let _ = result;
                SocketEvent::ProcessChanged
            },
            _ = ping.tick() => SocketEvent::Ping,
        };

        let keep_open = match event {
            SocketEvent::Client(Some(Ok(Message::Text(text)))) => {
                let valid = !text.contains('\n')
                    && serde_json::from_str::<Value>(&text).is_ok_and(|value| value.is_object());
                if !valid {
                    close(&mut sender, 1007, "each frame must be one JSON object").await;
                    false
                } else if session.process.send_line(text.to_string()).await.is_err() {
                    close(&mut sender, 1011, "Pi stdin is unavailable").await;
                    false
                } else {
                    true
                }
            }
            SocketEvent::Client(Some(Ok(Message::Ping(data)))) => {
                sender.send(Message::Pong(data)).await.is_ok()
            }
            SocketEvent::Client(Some(Ok(Message::Pong(_)))) => true,
            SocketEvent::Client(Some(Ok(Message::Close(_)) | Err(_)) | None)
            | SocketEvent::Pi(Err(broadcast::error::RecvError::Closed)) => false,
            SocketEvent::Client(Some(Ok(Message::Binary(_)))) => {
                close(&mut sender, 1003, "binary frames are unsupported").await;
                false
            }
            SocketEvent::Pi(Ok(line)) => sender.send(Message::Text(line.into())).await.is_ok(),
            SocketEvent::Pi(Err(broadcast::error::RecvError::Lagged(_))) => {
                close(&mut sender, 1011, "Pi output backlog exceeded").await;
                false
            }
            SocketEvent::LeaseChanged => {
                if *lease_generation.borrow() == captured_generation {
                    true
                } else {
                    close(&mut sender, 4001, "lease revoked").await;
                    false
                }
            }
            SocketEvent::ProcessChanged => {
                if matches!(
                    status.borrow().state,
                    ProcessState::Exited | ProcessState::Failed | ProcessState::Stopped
                ) {
                    close(&mut sender, 1011, "Pi process exited").await;
                    false
                } else {
                    true
                }
            }
            SocketEvent::Ping => sender.send(Message::Ping(Vec::new().into())).await.is_ok(),
        };
        if !keep_open {
            break;
        }
    }
    session.disconnect_lease(lease_id).await;
}

async fn close(
    sender: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    code: u16,
    reason: &'static str,
) {
    let _ = sender
        .send(Message::Close(Some(CloseFrame {
            code,
            reason: reason.into(),
        })))
        .await;
}

fn authorize(state: &AppState, headers: &HeaderMap) -> std::result::Result<(), ApiError> {
    let supplied = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    let authorized = supplied.is_some_and(|value| {
        value.len() == state.config.token.len()
            && value.as_bytes().ct_eq(state.config.token.as_bytes()).into()
    });
    if authorized {
        Ok(())
    } else {
        Err(ApiError {
            status: StatusCode::UNAUTHORIZED,
            code: "unauthorized",
            message: "missing or invalid bearer token".into(),
        })
    }
}
