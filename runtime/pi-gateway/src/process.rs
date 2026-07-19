use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::{Mutex, RwLock, broadcast, mpsc, watch};
use tokio_util::sync::CancellationToken;

use crate::config::Config;
use crate::{Error, Result};

const OUTPUT_BUFFER_LINES: usize = 2_048;
const INPUT_BUFFER_LINES: usize = 256;
const RUNTIME_BEARER_ENV_NAMES: &[&str] = &[
    "OPENHOUSE_PI_TOKEN",
    "OPENHOUSE_PI_TOKEN_FILE",
    "OPENHOUSE_PI_RUNTIME_TOKEN",
    "OPENHOUSE_RUNTIME_TOKEN",
    "OPENHOUSE_GATEWAY_TOKEN",
    "OPENHOUSE_ACCESS_TOKEN",
    "OPENHOUSE_PI_ACCESS_TOKEN",
];

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ProcessState {
    Stopped,
    Starting,
    Running,
    Exited,
    Failed,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExitObservation {
    pub at_epoch_ms: u64,
    pub exit_code: Option<i32>,
    pub expected: bool,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessStatus {
    pub state: ProcessState,
    pub pid: Option<u32>,
    pub started_at_epoch_ms: Option<u64>,
    pub restart_count: u64,
    pub stderr_line_count: u64,
    pub last_exit: Option<ExitObservation>,
}

impl Default for ProcessStatus {
    fn default() -> Self {
        Self {
            state: ProcessState::Stopped,
            pid: None,
            started_at_epoch_ms: None,
            restart_count: 0,
            stderr_line_count: 0,
            last_exit: None,
        }
    }
}

#[derive(Debug)]
struct ProcessSlot {
    generation: u64,
    input: Option<mpsc::Sender<String>>,
    cancellation: Option<CancellationToken>,
}

struct Inner {
    config: Config,
    session_path: RwLock<Option<PathBuf>>,
    observed_session_tx: watch::Sender<Option<PathBuf>>,
    working_dir: PathBuf,
    android_bridge_proxy_url: Arc<str>,
    android_bridge_helper: Option<PathBuf>,
    slot: Mutex<ProcessSlot>,
    status: RwLock<ProcessStatus>,
    status_tx: watch::Sender<ProcessStatus>,
    output_tx: broadcast::Sender<String>,
    last_activity_epoch_ms: Arc<AtomicU64>,
    agent_active: Arc<AtomicBool>,
}

#[derive(Clone)]
pub struct ProcessController {
    inner: Arc<Inner>,
}

impl ProcessController {
    pub fn new(
        config: Config,
        session_path: Option<PathBuf>,
        working_dir: PathBuf,
        android_bridge_proxy_url: Arc<str>,
    ) -> Self {
        let initial = ProcessStatus::default();
        let (status_tx, _) = watch::channel(initial.clone());
        let (output_tx, _) = broadcast::channel(OUTPUT_BUFFER_LINES);
        let (observed_session_tx, _) = watch::channel(session_path.clone());
        let android_bridge_helper = config
            .extension_paths
            .iter()
            .map(|path| path.join("android-bridge-request.sh"))
            .find(|path| path.is_file());
        Self {
            inner: Arc::new(Inner {
                config,
                session_path: RwLock::new(session_path),
                observed_session_tx,
                working_dir,
                android_bridge_proxy_url,
                android_bridge_helper,
                slot: Mutex::new(ProcessSlot {
                    generation: 0,
                    input: None,
                    cancellation: None,
                }),
                status: RwLock::new(initial),
                status_tx,
                output_tx,
                last_activity_epoch_ms: Arc::new(AtomicU64::new(now_epoch_ms())),
                agent_active: Arc::new(AtomicBool::new(false)),
            }),
        }
    }

    pub fn subscribe_output(&self) -> broadcast::Receiver<String> {
        self.inner.output_tx.subscribe()
    }

    pub fn subscribe_status(&self) -> watch::Receiver<ProcessStatus> {
        self.inner.status_tx.subscribe()
    }

    pub async fn status(&self) -> ProcessStatus {
        self.inner.status.read().await.clone()
    }

    pub fn last_activity_epoch_ms(&self) -> u64 {
        self.inner.last_activity_epoch_ms.load(Ordering::Relaxed)
    }

    pub fn touch(&self) {
        self.inner
            .last_activity_epoch_ms
            .store(now_epoch_ms(), Ordering::Relaxed);
    }

    pub fn is_agent_active(&self) -> bool {
        self.inner.agent_active.load(Ordering::Relaxed)
    }

    pub fn observed_session_path(&self) -> Option<PathBuf> {
        self.inner.observed_session_tx.borrow().clone()
    }

    pub async fn ensure_running(&self) -> Result<()> {
        let mut slot = self.inner.slot.lock().await;
        if slot.input.is_some() {
            return Ok(());
        }

        self.replace_status(|status| {
            status.state = ProcessState::Starting;
            status.pid = None;
        })
        .await;

        let mut command = Command::new(&self.inner.config.pi_bin);
        command
            .arg("--mode")
            .arg("rpc")
            .arg("--session-dir")
            .arg(&self.inner.config.sessions_dir)
            .current_dir(&self.inner.working_dir)
            .env("PI_SESSIONS_DIR", &self.inner.config.sessions_dir)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        remove_runtime_bearer_env(&mut command);
        if let Some(path) = self.inner.session_path.read().await.as_ref() {
            command.arg("--session").arg(path);
        }
        for extension in &self.inner.config.extension_paths {
            command.arg("--extension").arg(extension);
        }
        command.env(
            "OPENHOUSE_ANDROID_BRIDGE_URL",
            self.inner.android_bridge_proxy_url.as_ref(),
        );
        if let Some(helper) = &self.inner.android_bridge_helper {
            command.env("OPENHOUSE_ANDROID_BRIDGE_HELPER", helper);
        }

        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(error) => {
                let reason = format!("failed to start Pi: {error}");
                self.replace_status(|status| {
                    status.state = ProcessState::Failed;
                    status.pid = None;
                    status.last_exit = Some(ExitObservation {
                        at_epoch_ms: now_epoch_ms(),
                        exit_code: None,
                        expected: false,
                        reason: reason.clone(),
                    });
                })
                .await;
                return Err(Error::Process(reason));
            }
        };

        let pid = child.id();
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| Error::Process("Pi stdin was not piped".into()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| Error::Process("Pi stdout was not piped".into()))?;
        let stderr = child
            .stderr
            .take()
            .ok_or_else(|| Error::Process("Pi stderr was not piped".into()))?;

        slot.generation = slot.generation.wrapping_add(1);
        let generation = slot.generation;
        let (input_tx, input_rx) = mpsc::channel(INPUT_BUFFER_LINES);
        let cancellation = CancellationToken::new();
        slot.input = Some(input_tx.clone());
        slot.cancellation = Some(cancellation.clone());

        self.replace_status(|status| {
            status.state = ProcessState::Running;
            status.pid = pid;
            status.started_at_epoch_ms = Some(now_epoch_ms());
            status.restart_count = status.restart_count.saturating_add(1);
            status.stderr_line_count = 0;
        })
        .await;
        self.touch();

        let inner = Arc::clone(&self.inner);
        tokio::spawn(async move {
            run_child(
                inner,
                generation,
                child,
                stdin,
                stdout,
                stderr,
                input_rx,
                input_tx,
                cancellation,
            )
            .await;
        });
        Ok(())
    }

    pub async fn send_line(&self, line: String) -> Result<()> {
        let sender = self
            .inner
            .slot
            .lock()
            .await
            .input
            .clone()
            .ok_or_else(|| Error::Process("Pi process is not running".into()))?;
        sender
            .send(line)
            .await
            .map_err(|_| Error::Process("Pi stdin is closed".into()))?;
        self.touch();
        Ok(())
    }

    pub async fn stop(&self) {
        let cancellation = self.inner.slot.lock().await.cancellation.clone();
        if let Some(cancellation) = cancellation {
            cancellation.cancel();
        }
    }

    async fn replace_status(&self, update: impl FnOnce(&mut ProcessStatus)) {
        let mut status = self.inner.status.write().await;
        update(&mut status);
        self.inner.status_tx.send_replace(status.clone());
    }
}

#[allow(clippy::too_many_arguments)]
#[allow(clippy::too_many_lines)]
async fn run_child(
    inner: Arc<Inner>,
    generation: u64,
    mut child: tokio::process::Child,
    mut stdin: tokio::process::ChildStdin,
    stdout: tokio::process::ChildStdout,
    stderr: tokio::process::ChildStderr,
    mut input_rx: mpsc::Receiver<String>,
    internal_input: mpsc::Sender<String>,
    cancellation: CancellationToken,
) {
    let output_tx = inner.output_tx.clone();
    let output_activity = Arc::clone(&inner.last_activity_epoch_ms);
    let output_inner = Arc::clone(&inner);
    let internal_id = format!("__openhouse_runtime_state_{generation}");
    let stdout_task = tokio::spawn(async move {
        let mut lines = BufReader::new(stdout).lines();
        let mut last_state_query_epoch_ms = 0_u64;
        while let Ok(Some(line)) = lines.next_line().await {
            output_activity.store(now_epoch_ms(), Ordering::Relaxed);
            let parsed = serde_json::from_str::<serde_json::Value>(&line).ok();
            let message_type = parsed
                .as_ref()
                .and_then(|value| value.get("type"))
                .and_then(serde_json::Value::as_str);
            let is_internal = parsed.as_ref().is_some_and(|value| {
                value.get("id").and_then(serde_json::Value::as_str) == Some(&internal_id)
            });
            match message_type {
                Some("agent_start") => output_inner.agent_active.store(true, Ordering::Relaxed),
                Some("agent_end") => {
                    output_inner.agent_active.store(false, Ordering::Relaxed);
                }
                _ => {}
            }
            if let Some(path) = parsed.as_ref().and_then(extract_session_path) {
                *output_inner.session_path.write().await = Some(path.clone());
                output_inner.observed_session_tx.send_replace(Some(path));
            }
            if is_internal {
                continue;
            }
            let now = now_epoch_ms();
            if output_inner.observed_session_tx.borrow().is_none()
                && now.saturating_sub(last_state_query_epoch_ms) >= 250
            {
                let request = serde_json::json!({
                    "id": internal_id,
                    "type": "get_state",
                })
                .to_string();
                if internal_input.try_send(request).is_ok() {
                    last_state_query_epoch_ms = now;
                }
            }
            let _ = output_tx.send(line);
        }
    });

    let stderr_status = Arc::clone(&inner);
    let stderr_task = tokio::spawn(async move {
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(_line)) = lines.next_line().await {
            let mut status = stderr_status.status.write().await;
            status.stderr_line_count = status.stderr_line_count.saturating_add(1);
            stderr_status.status_tx.send_replace(status.clone());
        }
    });

    let writer_task = tokio::spawn(async move {
        while let Some(line) = input_rx.recv().await {
            stdin.write_all(line.as_bytes()).await?;
            stdin.write_all(b"\n").await?;
            stdin.flush().await?;
        }
        Ok::<(), std::io::Error>(())
    });

    let mut expected = false;
    let status = tokio::select! {
        result = child.wait() => result,
        () = cancellation.cancelled() => {
            expected = true;
            let _ = child.start_kill();
            if let Ok(result) = tokio::time::timeout(inner.config.settings.shutdown_timeout(), child.wait()).await {
                result
            } else {
                let _ = child.kill().await;
                child.wait().await
            }
        }
    };

    writer_task.abort();
    let _ = stdout_task.await;
    let _ = stderr_task.await;

    {
        let mut slot = inner.slot.lock().await;
        if slot.generation == generation {
            slot.input = None;
            slot.cancellation = None;
        }
    }

    let mut current = inner.status.write().await;
    inner.agent_active.store(false, Ordering::Relaxed);
    current.pid = None;
    match status {
        Ok(exit) => {
            current.state = ProcessState::Exited;
            current.last_exit = Some(ExitObservation {
                at_epoch_ms: now_epoch_ms(),
                exit_code: exit.code(),
                expected,
                reason: if expected {
                    "runtime requested process shutdown".into()
                } else {
                    "Pi process exited".into()
                },
            });
        }
        Err(error) => {
            current.state = ProcessState::Failed;
            current.last_exit = Some(ExitObservation {
                at_epoch_ms: now_epoch_ms(),
                exit_code: None,
                expected,
                reason: format!("failed to observe Pi exit: {error}"),
            });
        }
    }
    inner.status_tx.send_replace(current.clone());
}

fn extract_session_path(value: &serde_json::Value) -> Option<PathBuf> {
    let path = value
        .get("data")
        .and_then(|data| data.get("sessionFile"))
        .or_else(|| value.get("sessionFile"))?
        .as_str()?;
    (!path.is_empty()).then(|| PathBuf::from(path))
}

fn remove_runtime_bearer_env(command: &mut Command) {
    for name in RUNTIME_BEARER_ENV_NAMES {
        command.env_remove(name);
    }
}

pub fn now_epoch_ms() -> u64 {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis();
    u64::try_from(millis).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pi_command_explicitly_removes_runtime_bearer_environment() {
        let mut command = Command::new("pi");
        for name in RUNTIME_BEARER_ENV_NAMES {
            command.env(name, "must-not-reach-pi");
        }
        remove_runtime_bearer_env(&mut command);
        let environment: std::collections::HashMap<_, _> = command
            .as_std()
            .get_envs()
            .map(|(name, value)| (name.to_owned(), value.map(std::ffi::OsStr::to_owned)))
            .collect();
        for name in RUNTIME_BEARER_ENV_NAMES {
            assert_eq!(environment.get(std::ffi::OsStr::new(name)), Some(&None));
        }
    }
}
