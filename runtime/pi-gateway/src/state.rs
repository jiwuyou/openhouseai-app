use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, RwLock, watch};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::config::{Config, atomic_json_write};
use crate::process::{ProcessController, ProcessState, ProcessStatus, now_epoch_ms};
use crate::{Error, Result};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionDescriptor {
    pub session_id: String,
    pub session_path: Option<String>,
    #[serde(default)]
    pub cwd: Option<String>,
    pub created_at_epoch_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionView {
    #[serde(flatten)]
    pub descriptor: SessionDescriptor,
    pub process: ProcessStatus,
    pub connected_clients: usize,
    pub lease: Option<LeaseView>,
    pub last_activity_epoch_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LeaseView {
    pub lease_id: Uuid,
    pub client_id: String,
    pub connected: bool,
    pub acquired_at_epoch_ms: u64,
    pub disconnected_at_epoch_ms: Option<u64>,
}

#[derive(Debug, Clone)]
struct Lease {
    lease_id: Uuid,
    client_id: String,
    connected: bool,
    acquired_at_epoch_ms: u64,
    disconnected_at_epoch_ms: Option<u64>,
    generation: u64,
}

impl From<&Lease> for LeaseView {
    fn from(lease: &Lease) -> Self {
        Self {
            lease_id: lease.lease_id,
            client_id: lease.client_id.clone(),
            connected: lease.connected,
            acquired_at_epoch_ms: lease.acquired_at_epoch_ms,
            disconnected_at_epoch_ms: lease.disconnected_at_epoch_ms,
        }
    }
}

pub struct ManagedSession {
    id: String,
    descriptor: RwLock<SessionDescriptor>,
    pub process: ProcessController,
    lease: Mutex<Option<Lease>>,
    lease_generation_tx: watch::Sender<u64>,
    connected_clients: AtomicUsize,
}

pub struct AppState {
    pub config: Config,
    pub started_at_epoch_ms: u64,
    sessions: RwLock<HashMap<String, Arc<ManagedSession>>>,
    android_bridge_registration: RwLock<Option<AndroidBridgeRegistration>>,
    bridge_proxy_id: Uuid,
    pub bridge_http: reqwest::Client,
    shutdown: CancellationToken,
    registry_write: Mutex<()>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CreateSession {
    pub session_id: Option<String>,
    pub session_path: Option<String>,
    pub cwd: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RegisterAndroidBridge {
    pub client_id: String,
    pub port: u16,
    pub token: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidBridgeView {
    pub client_id: String,
    pub port: u16,
    pub registered_at_epoch_ms: u64,
}

#[derive(Clone)]
struct AndroidBridgeRegistration {
    view: AndroidBridgeView,
    token: Arc<str>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AcquireLease {
    pub session_id: String,
    pub client_id: String,
    #[serde(default)]
    pub takeover: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LeaseGrant {
    pub lease_id: Uuid,
    pub session_id: String,
    pub client_id: String,
    pub ws_path: String,
    pub disconnect_grace_seconds: u64,
}

impl AppState {
    pub fn new(config: Config) -> Result<Arc<Self>> {
        let descriptors = load_registry(&config.state_dir.join("sessions.json"))?;
        let bridge_proxy_id = Uuid::new_v4();
        let bridge_proxy_url: Arc<str> = Arc::from(format!(
            "http://{}/internal/v1/android-bridge/{bridge_proxy_id}",
            config.listen
        ));
        let sessions = descriptors
            .into_iter()
            .filter_map(|descriptor| {
                let path = match descriptor.session_path.as_deref() {
                    Some(relative) => match resolve_session_path(&config.sessions_dir, relative) {
                        Ok(path) => Some(path),
                        Err(error) => {
                            tracing::warn!(session_id = %descriptor.session_id, %error, "ignoring invalid persisted session mapping");
                            return None;
                        }
                    },
                    None => None,
                };
                let id = descriptor.session_id.clone();
                let working_dir = descriptor
                    .cwd
                    .as_deref()
                    .and_then(|cwd| resolve_working_dir(cwd).ok())
                    .unwrap_or_else(|| config.pi_working_dir.clone());
                Some((
                    id,
                    Arc::new(ManagedSession::new(
                        &config,
                        descriptor,
                        path,
                        working_dir,
                        Arc::clone(&bridge_proxy_url),
                    )),
                ))
            })
            .collect();
        Ok(Arc::new(Self {
            config,
            started_at_epoch_ms: now_epoch_ms(),
            sessions: RwLock::new(sessions),
            android_bridge_registration: RwLock::new(None),
            bridge_proxy_id,
            bridge_http: reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(5))
                .timeout(Duration::from_secs(70))
                .build()
                .map_err(|error| {
                    Error::Config(format!("failed to build Bridge client: {error}"))
                })?,
            shutdown: CancellationToken::new(),
            registry_write: Mutex::new(()),
        }))
    }

    pub fn start_background_tasks(self: &Arc<Self>) {
        let state = Arc::clone(self);
        tokio::spawn(async move { state.reaper_loop().await });
        let state = Arc::clone(self);
        tokio::spawn(async move { state.path_sync_loop().await });
    }

    pub async fn shutdown(&self) {
        self.shutdown.cancel();
        if let Err(error) = self.sync_observed_paths().await {
            tracing::warn!(%error, "failed to persist observed session paths during shutdown");
        }
        let sessions: Vec<_> = self.sessions.read().await.values().cloned().collect();
        for session in sessions {
            session.process.stop().await;
        }
    }

    pub async fn create_session(&self, request: CreateSession) -> Result<Arc<ManagedSession>> {
        let session_id = request
            .session_id
            .unwrap_or_else(|| Uuid::new_v4().to_string());
        validate_session_id(&session_id)?;
        let (relative, resolved) = match request.session_path {
            Some(path) => {
                let resolved = resolve_session_path(&self.config.sessions_dir, &path)?;
                let relative = resolved
                    .strip_prefix(&self.config.sessions_dir)
                    .map_err(|_| {
                        Error::InvalidRequest("session path is outside session root".into())
                    })?
                    .to_string_lossy()
                    .into_owned();
                (Some(relative), Some(resolved))
            }
            None => (None, None),
        };

        if let Some(relative_path) = relative.as_deref() {
            if let Err(error) = self.sync_observed_paths().await {
                tracing::warn!(%error, "failed to sync session paths before attach");
            }
            let existing: Vec<_> = self.sessions.read().await.values().cloned().collect();
            for session in existing {
                if session.descriptor.read().await.session_path.as_deref() == Some(relative_path) {
                    return Ok(session);
                }
            }
        }

        let working_dir = request.cwd.as_deref().map_or_else(
            || Ok(self.config.pi_working_dir.clone()),
            resolve_working_dir,
        )?;
        let descriptor = SessionDescriptor {
            session_id: session_id.clone(),
            session_path: relative,
            cwd: Some(working_dir.display().to_string()),
            created_at_epoch_ms: now_epoch_ms(),
        };
        let session = Arc::new(ManagedSession::new(
            &self.config,
            descriptor,
            resolved,
            working_dir,
            Arc::from(format!(
                "http://{}/internal/v1/android-bridge/{}",
                self.config.listen, self.bridge_proxy_id
            )),
        ));
        let mut sessions = self.sessions.write().await;
        if sessions.contains_key(&session_id) {
            return Err(Error::Conflict(format!(
                "session '{session_id}' already exists"
            )));
        }
        sessions.insert(session_id, Arc::clone(&session));
        drop(sessions);
        self.persist_registry().await?;
        Ok(session)
    }

    pub async fn get_or_create_session(&self, session_id: &str) -> Result<Arc<ManagedSession>> {
        validate_session_id(session_id)?;
        if let Some(session) = self.sessions.read().await.get(session_id).cloned() {
            return Ok(session);
        }
        self.create_session(CreateSession {
            session_id: Some(session_id.to_owned()),
            session_path: None,
            cwd: None,
        })
        .await
    }

    pub async fn get_session(&self, session_id: &str) -> Result<Arc<ManagedSession>> {
        self.sessions
            .read()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| Error::NotFound(format!("session '{session_id}'")))
    }

    pub async fn remove_session(&self, session_id: &str) -> Result<()> {
        let session = self
            .sessions
            .write()
            .await
            .remove(session_id)
            .ok_or_else(|| Error::NotFound(format!("session '{session_id}'")))?;
        session.process.stop().await;
        self.persist_registry().await?;
        Ok(())
    }

    pub async fn session_views(&self) -> Vec<SessionView> {
        if let Err(error) = self.sync_observed_paths().await {
            tracing::warn!(%error, "failed to persist observed Pi session path");
        }
        let sessions: Vec<_> = self.sessions.read().await.values().cloned().collect();
        let mut views = Vec::with_capacity(sessions.len());
        for session in sessions {
            views.push(session.view().await);
        }
        views.sort_by(|left, right| left.descriptor.session_id.cmp(&right.descriptor.session_id));
        views
    }

    pub async fn acquire_lease(&self, request: AcquireLease) -> Result<LeaseGrant> {
        validate_client_id(&request.client_id)?;
        let session = self.get_or_create_session(&request.session_id).await?;
        let grant = session
            .acquire_lease(
                request.client_id,
                request.takeover,
                self.config.settings.disconnect_grace(),
            )
            .await?;
        session.process.ensure_running().await?;
        Ok(grant)
    }

    pub async fn find_lease(&self, lease_id: Uuid) -> Result<Arc<ManagedSession>> {
        let sessions: Vec<_> = self.sessions.read().await.values().cloned().collect();
        for session in sessions {
            if session.has_lease(lease_id).await {
                return Ok(session);
            }
        }
        Err(Error::NotFound("lease".into()))
    }

    pub async fn release_lease(&self, lease_id: Uuid) -> Result<()> {
        let session = self.find_lease(lease_id).await?;
        session.release_lease(lease_id).await
    }

    pub async fn register_android_bridge(
        &self,
        request: RegisterAndroidBridge,
    ) -> Result<AndroidBridgeView> {
        validate_client_id(&request.client_id)?;
        if request.port == 0 {
            return Err(Error::InvalidRequest(
                "Android Bridge port must be between 1 and 65535".into(),
            ));
        }
        if request.token.len() < 32 || request.token.contains(char::is_whitespace) {
            return Err(Error::InvalidRequest(
                "Android Bridge token must contain at least 32 non-whitespace characters".into(),
            ));
        }
        let view = AndroidBridgeView {
            client_id: request.client_id,
            port: request.port,
            registered_at_epoch_ms: now_epoch_ms(),
        };
        let registration = AndroidBridgeRegistration {
            view: view.clone(),
            token: Arc::from(request.token),
        };
        *self.android_bridge_registration.write().await = Some(registration);
        Ok(view)
    }

    pub async fn unregister_android_bridge(&self) {
        *self.android_bridge_registration.write().await = None;
    }

    pub async fn android_bridge_view(&self) -> Option<AndroidBridgeView> {
        self.android_bridge_registration
            .read()
            .await
            .as_ref()
            .map(|registration| registration.view.clone())
    }

    pub fn bridge_proxy_id(&self) -> Uuid {
        self.bridge_proxy_id
    }

    pub async fn android_bridge_target(&self) -> Result<(u16, Arc<str>)> {
        self.android_bridge_registration
            .read()
            .await
            .as_ref()
            .map(|registration| (registration.view.port, Arc::clone(&registration.token)))
            .ok_or_else(|| Error::NotFound("Android Bridge is not registered".into()))
    }

    async fn persist_registry(&self) -> Result<()> {
        let _write_guard = self.registry_write.lock().await;
        let sessions: Vec<_> = self.sessions.read().await.values().cloned().collect();
        let mut descriptors = Vec::with_capacity(sessions.len());
        for session in sessions {
            descriptors.push(session.descriptor.read().await.clone());
        }
        let path = self.config.state_dir.join("sessions.json");
        tokio::task::spawn_blocking(move || atomic_json_write(&path, &descriptors))
            .await
            .map_err(|error| Error::Io(std::io::Error::other(error)))??;
        Ok(())
    }

    async fn reaper_loop(self: Arc<Self>) {
        let mut interval = tokio::time::interval(Duration::from_secs(5));
        loop {
            tokio::select! {
                () = self.shutdown.cancelled() => break,
                _ = interval.tick() => self.reap_once().await,
            }
        }
    }

    async fn path_sync_loop(self: Arc<Self>) {
        let mut interval = tokio::time::interval(Duration::from_millis(250));
        loop {
            tokio::select! {
                () = self.shutdown.cancelled() => break,
                _ = interval.tick() => {
                    if let Err(error) = self.sync_observed_paths().await {
                        tracing::warn!(%error, "failed to persist observed Pi session path");
                    }
                },
            }
        }
    }

    async fn reap_once(&self) {
        if let Err(error) = self.sync_observed_paths().await {
            tracing::warn!(%error, "failed to persist observed Pi session path");
        }
        let sessions: Vec<_> = self.sessions.read().await.values().cloned().collect();
        let now = now_epoch_ms();
        let timeout_ms =
            u64::try_from(self.config.settings.idle_timeout().as_millis()).unwrap_or(u64::MAX);
        for session in sessions {
            session
                .expire_disconnected_lease(self.config.settings.disconnect_grace())
                .await;
            if session.connected_clients.load(Ordering::Relaxed) == 0
                && now.saturating_sub(session.process.last_activity_epoch_ms()) >= timeout_ms
                && session.process.status().await.state == ProcessState::Running
                && !session.process.is_agent_active()
            {
                session.process.stop().await;
            }
        }
    }

    async fn sync_observed_paths(&self) -> Result<()> {
        let sessions: Vec<_> = self.sessions.read().await.values().cloned().collect();
        let mut changed = false;
        for session in sessions {
            let Some(observed) = session.process.observed_session_path() else {
                continue;
            };
            if !observed.is_file() {
                continue;
            }
            let observed = resolve_session_path(
                &self.config.sessions_dir,
                observed.to_string_lossy().as_ref(),
            )?;
            let relative = observed
                .strip_prefix(&self.config.sessions_dir)
                .map_err(|_| Error::InvalidRequest("session path escaped session root".into()))?
                .to_string_lossy()
                .into_owned();
            let mut descriptor = session.descriptor.write().await;
            if descriptor.session_path.as_deref() != Some(&relative) {
                descriptor.session_path = Some(relative);
                changed = true;
            }
        }
        if changed {
            self.persist_registry().await?;
        }
        Ok(())
    }
}

impl ManagedSession {
    fn new(
        config: &Config,
        descriptor: SessionDescriptor,
        path: Option<PathBuf>,
        working_dir: PathBuf,
        android_bridge_proxy_url: Arc<str>,
    ) -> Self {
        let (lease_generation_tx, _) = watch::channel(0);
        Self {
            id: descriptor.session_id.clone(),
            descriptor: RwLock::new(descriptor),
            process: ProcessController::new(
                config.clone(),
                path,
                working_dir,
                android_bridge_proxy_url,
            ),
            lease: Mutex::new(None),
            lease_generation_tx,
            connected_clients: AtomicUsize::new(0),
        }
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    async fn view(&self) -> SessionView {
        let lease = self.lease.lock().await.as_ref().map(LeaseView::from);
        SessionView {
            descriptor: self.descriptor.read().await.clone(),
            process: self.process.status().await,
            connected_clients: self.connected_clients.load(Ordering::Relaxed),
            lease,
            last_activity_epoch_ms: self.process.last_activity_epoch_ms(),
        }
    }

    async fn acquire_lease(
        &self,
        client_id: String,
        takeover: bool,
        grace: Duration,
    ) -> Result<LeaseGrant> {
        let now = now_epoch_ms();
        let grace_ms = u64::try_from(grace.as_millis()).unwrap_or(u64::MAX);
        let mut current = self.lease.lock().await;
        if let Some(existing) = current.as_ref() {
            let reconnectable = existing.client_id == client_id && !existing.connected;
            let expired = !existing.connected
                && existing
                    .disconnected_at_epoch_ms
                    .is_some_and(|at| now.saturating_sub(at) >= grace_ms);
            if reconnectable && !expired {
                return Ok(self.grant(existing, grace));
            }
            if !takeover && !expired {
                return Err(Error::Conflict("lease_conflict".into()));
            }
            if existing.connected {
                self.connected_clients.fetch_sub(1, Ordering::Relaxed);
            }
        }

        let generation = current
            .as_ref()
            .map_or(1, |lease| lease.generation.wrapping_add(1));
        let lease = Lease {
            lease_id: Uuid::new_v4(),
            client_id,
            connected: false,
            acquired_at_epoch_ms: now,
            disconnected_at_epoch_ms: None,
            generation,
        };
        self.lease_generation_tx.send_replace(generation);
        let grant = self.grant(&lease, grace);
        *current = Some(lease);
        Ok(grant)
    }

    fn grant(&self, lease: &Lease, grace: Duration) -> LeaseGrant {
        LeaseGrant {
            lease_id: lease.lease_id,
            session_id: self.id.clone(),
            client_id: lease.client_id.clone(),
            ws_path: format!("/ws/rpc/{}", lease.lease_id),
            disconnect_grace_seconds: grace.as_secs(),
        }
    }

    async fn has_lease(&self, lease_id: Uuid) -> bool {
        self.lease
            .lock()
            .await
            .as_ref()
            .is_some_and(|lease| lease.lease_id == lease_id)
    }

    pub async fn connect_lease(&self, lease_id: Uuid) -> Result<watch::Receiver<u64>> {
        let mut lease = self.lease.lock().await;
        let current = lease
            .as_mut()
            .filter(|lease| lease.lease_id == lease_id)
            .ok_or_else(|| Error::NotFound("lease".into()))?;
        if current.connected {
            return Err(Error::Conflict("lease_conflict".into()));
        }
        current.connected = true;
        current.disconnected_at_epoch_ms = None;
        self.connected_clients.fetch_add(1, Ordering::Relaxed);
        self.process.touch();
        Ok(self.lease_generation_tx.subscribe())
    }

    pub async fn disconnect_lease(&self, lease_id: Uuid) {
        let mut lease = self.lease.lock().await;
        if let Some(current) = lease.as_mut()
            && current.lease_id == lease_id
            && current.connected
        {
            current.connected = false;
            current.disconnected_at_epoch_ms = Some(now_epoch_ms());
            self.connected_clients.fetch_sub(1, Ordering::Relaxed);
            self.process.touch();
        }
    }

    async fn release_lease(&self, lease_id: Uuid) -> Result<()> {
        let mut lease = self.lease.lock().await;
        if lease
            .as_ref()
            .is_none_or(|lease| lease.lease_id != lease_id)
        {
            return Err(Error::NotFound("lease".into()));
        }
        if lease.as_ref().is_some_and(|lease| lease.connected) {
            self.connected_clients.fetch_sub(1, Ordering::Relaxed);
        }
        let next_generation = lease
            .as_ref()
            .map_or(1, |lease| lease.generation.wrapping_add(1));
        *lease = None;
        self.lease_generation_tx.send_replace(next_generation);
        Ok(())
    }

    async fn expire_disconnected_lease(&self, grace: Duration) {
        let now = now_epoch_ms();
        let grace_ms = u64::try_from(grace.as_millis()).unwrap_or(u64::MAX);
        let mut lease = self.lease.lock().await;
        let should_expire = lease.as_ref().is_some_and(|lease| {
            !lease.connected
                && lease
                    .disconnected_at_epoch_ms
                    .is_some_and(|at| now.saturating_sub(at) >= grace_ms)
        });
        if should_expire {
            let next_generation = lease
                .as_ref()
                .map_or(1, |lease| lease.generation.wrapping_add(1));
            *lease = None;
            self.lease_generation_tx.send_replace(next_generation);
        }
    }
}

fn validate_session_id(id: &str) -> Result<()> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        return Err(Error::InvalidRequest(
            "sessionId must be 1-128 ASCII letters, digits, '-' or '_'".into(),
        ));
    }
    Ok(())
}

fn validate_client_id(id: &str) -> Result<()> {
    if id.is_empty() || id.len() > 256 || id.chars().any(char::is_control) {
        return Err(Error::InvalidRequest(
            "clientId must contain 1-256 non-control characters".into(),
        ));
    }
    Ok(())
}

fn resolve_session_path(root: &Path, supplied: &str) -> Result<PathBuf> {
    let supplied = Path::new(supplied);
    let joined = if supplied.is_absolute() {
        supplied.to_owned()
    } else {
        root.join(supplied)
    };
    if !joined.is_file() {
        return Err(Error::InvalidRequest(format!(
            "session file does not exist: {}",
            joined.display()
        )));
    }
    let canonical = fs::canonicalize(joined)?;
    if !canonical.starts_with(root) {
        return Err(Error::InvalidRequest(
            "session path is outside configured sessions directory".into(),
        ));
    }
    if canonical.extension().and_then(|value| value.to_str()) != Some("jsonl") {
        return Err(Error::InvalidRequest(
            "session path must name a .jsonl file".into(),
        ));
    }
    Ok(canonical)
}

fn resolve_working_dir(supplied: &str) -> Result<PathBuf> {
    let path = fs::canonicalize(supplied)?;
    if !path.is_dir() {
        return Err(Error::InvalidRequest(format!(
            "working directory does not exist: {}",
            path.display()
        )));
    }
    Ok(path)
}

fn load_registry(path: &Path) -> Result<Vec<SessionDescriptor>> {
    if !path.exists() {
        return Ok(Vec::new());
    }
    Ok(serde_json::from_slice(&fs::read(path)?)?)
}
