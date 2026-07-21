use asupersync::runtime::reactor::create_reactor;
use asupersync::runtime::{RuntimeBuilder, RuntimeHandle};
use async_trait::async_trait;
use futures::channel::oneshot;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use pi::agent::AgentEvent;
use pi::compaction::ResolvedCompactionSettings;
use pi::model::{
    AssistantMessage, AssistantMessageEvent, ContentBlock, Message, StopReason, TextContent, Usage,
    UserContent, UserMessage,
};
use pi::models::ModelEntry;
use pi::provider::{InputType, Model, ModelCost, Provider, StreamOptions};
use pi::sdk::{
    Agent, AgentConfig, AgentSession, AgentSessionHandle, EventListeners, Tool, ToolOutput,
    ToolRegistry, ToolUpdate,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, mpsc};

#[cfg(test)]
use futures::Stream;
#[cfg(test)]
use pi::provider::{Context as ProviderContext, StreamEvent};
#[cfg(test)]
use std::pin::Pin;

const EVENT_QUEUE_CAPACITY: usize = 4096;

type PendingSender = oneshot::Sender<AndroidToolCompletion>;

static SESSIONS: OnceLock<Mutex<HashMap<String, Arc<SessionController>>>> = OnceLock::new();
static PENDING_TOOLS: OnceLock<Mutex<HashMap<String, PendingAndroidTool>>> = OnceLock::new();
static ACTIVE_REQUESTS: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
static EVENTS: OnceLock<Mutex<EventQueue>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<String, Arc<SessionController>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn pending_tools() -> &'static Mutex<HashMap<String, PendingAndroidTool>> {
    PENDING_TOOLS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn active_requests() -> &'static Mutex<HashMap<String, String>> {
    ACTIVE_REQUESTS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn event_queue() -> &'static Mutex<EventQueue> {
    EVENTS.get_or_init(|| Mutex::new(EventQueue::default()))
}

#[derive(Default)]
struct EventQueue {
    values: VecDeque<Value>,
    dropped: u64,
}

impl EventQueue {
    fn push(&mut self, value: Value) -> bool {
        let overflowed = self.values.len() == EVENT_QUEUE_CAPACITY;
        if self.values.len() == EVENT_QUEUE_CAPACITY {
            self.values.pop_front();
            self.dropped = self.dropped.saturating_add(1);
        }
        self.values.push_back(value);
        overflowed
    }

    fn drain(&mut self, maximum: usize) -> Vec<Value> {
        if maximum == 0 {
            return Vec::new();
        }
        let mut output = Vec::with_capacity(maximum.min(self.values.len() + 1));
        if self.dropped > 0 {
            output.push(json!({
                "type": "event_queue_overflow",
                "chatId": "",
                "requestId": "",
                "dropped": std::mem::take(&mut self.dropped),
            }));
        }
        let remaining = maximum.saturating_sub(output.len()).min(self.values.len());
        output.extend(self.values.drain(..remaining));
        output
    }
}

fn push_event(event: Value) {
    let overflowed = event_queue()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .push(event);
    if overflowed {
        cancel_all_pending("event queue overflow");
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionConfig {
    chat_id: String,
    provider: String,
    api: String,
    model: String,
    base_url: String,
    #[serde(default)]
    api_key: String,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    system_prompt: String,
    #[serde(default)]
    max_tokens: Option<u32>,
    #[serde(default)]
    temperature: Option<f32>,
    #[serde(default = "default_context_window")]
    context_window: u32,
    #[serde(default)]
    thinking_level: Option<String>,
    #[serde(default)]
    tools: Vec<AndroidToolSpec>,
    #[serde(default)]
    history: Value,
    #[serde(default)]
    session_path: Option<String>,
    #[serde(default)]
    session_dir: Option<String>,
    working_directory: String,
    #[serde(default = "default_max_tool_iterations")]
    max_tool_iterations: usize,
    #[serde(default)]
    reasoning: bool,
    #[serde(default)]
    enable_compaction: bool,
    #[serde(default)]
    compaction_reserve_tokens: Option<u32>,
    #[serde(default)]
    compaction_keep_recent_tokens: Option<u32>,
}

const fn default_context_window() -> u32 {
    128_000
}

const fn default_max_tool_iterations() -> usize {
    50
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AndroidToolSpec {
    name: String,
    #[serde(default)]
    label: String,
    description: String,
    parameters: Value,
}

struct PendingAndroidTool {
    chat_id: String,
    sender: PendingSender,
}

struct AndroidToolCompletion {
    content: Vec<ContentBlock>,
    details: Option<Value>,
    is_error: bool,
}

fn bridge_android_tool_request_id(
    chat_id: &str,
    turn_request_id: &str,
    provider_tool_call_id: &str,
) -> String {
    format!(
        "android-tool:{}:{chat_id}:{}:{turn_request_id}:{}:{provider_tool_call_id}",
        chat_id.len(),
        turn_request_id.len(),
        provider_tool_call_id.len(),
    )
}

struct AndroidToolProxy {
    chat_id: String,
    spec: AndroidToolSpec,
}

#[async_trait]
impl Tool for AndroidToolProxy {
    fn name(&self) -> &str {
        &self.spec.name
    }

    fn label(&self) -> &str {
        if self.spec.label.is_empty() {
            &self.spec.name
        } else {
            &self.spec.label
        }
    }

    fn description(&self) -> &str {
        &self.spec.description
    }

    fn parameters(&self) -> Value {
        self.spec.parameters.clone()
    }

    async fn execute(
        &self,
        tool_call_id: &str,
        input: Value,
        _on_update: Option<Box<dyn Fn(ToolUpdate) + Send + Sync>>,
    ) -> pi::sdk::Result<ToolOutput> {
        if tool_call_id.is_empty() {
            return Err(pi::sdk::Error::tool(
                &self.spec.name,
                "provider toolCallId is required",
            ));
        }
        let turn_request_id = active_requests()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(&self.chat_id)
            .cloned()
            .ok_or_else(|| pi::sdk::Error::tool(&self.spec.name, "missing active request"))?;
        let pending_key =
            bridge_android_tool_request_id(&self.chat_id, &turn_request_id, tool_call_id);
        let (sender, receiver) = oneshot::channel();
        {
            let mut pending = pending_tools()
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if pending.contains_key(&pending_key) {
                return Err(pi::sdk::Error::tool(
                    &self.spec.name,
                    "duplicate provider toolCallId in active turn",
                ));
            }
            pending.insert(
                pending_key.clone(),
                PendingAndroidTool {
                    chat_id: self.chat_id.clone(),
                    sender,
                },
            );
        }
        let host_request_id = pending_key.clone();
        push_event(json!({
            "type": "host_tool_request",
            "chatId": self.chat_id,
            "requestId": turn_request_id,
            "toolCallId": pending_key,
            "hostRequestId": host_request_id,
            "providerToolCallId": tool_call_id,
            "toolName": self.spec.name,
            "args": input,
        }));

        let completion = receiver.await.unwrap_or_else(|_| AndroidToolCompletion {
            content: vec![ContentBlock::Text(TextContent::new(
                "Android tool request was cancelled",
            ))],
            details: Some(json!({"state": "cancelled"})),
            is_error: true,
        });
        Ok(ToolOutput {
            content: completion.content,
            details: completion.details,
            is_error: completion.is_error,
        })
    }
}

enum SessionCommand {
    Prompt {
        prompt: String,
        request_id: String,
    },
    Compact {
        instructions: String,
        request_id: String,
    },
    Close,
}

struct SessionController {
    chat_id: String,
    command_tx: mpsc::Sender<SessionCommand>,
    abort: Mutex<Option<pi::sdk::AbortHandle>>,
    busy: AtomicBool,
    cancelled: AtomicBool,
    api_key: String,
}

#[derive(Clone)]
struct OpenedSession {
    session_id: String,
    session_path: Option<String>,
    provider: String,
    model: String,
    message_count: usize,
}

fn validate_config(config: &SessionConfig) -> Result<(), String> {
    if config.chat_id.trim().is_empty() {
        return Err("chatId is required".to_string());
    }
    if config.provider.trim().is_empty() {
        return Err("provider is required".to_string());
    }
    if config.api.trim().is_empty() {
        return Err("api is required".to_string());
    }
    if config.model.trim().is_empty() {
        return Err("model is required".to_string());
    }
    if config.provider.eq_ignore_ascii_case("operit-host")
        || config.api.eq_ignore_ascii_case("operit-host")
    {
        return Err(
            "operit-host is not a supported Pi provider; configure a direct remote provider"
                .to_string(),
        );
    }
    if config.base_url.trim().is_empty() {
        return Err("baseUrl is required".to_string());
    }
    if config.working_directory.trim().is_empty() {
        return Err("workingDirectory is required".to_string());
    }
    if config.context_window == 0 {
        return Err("contextWindow must be greater than zero".to_string());
    }
    if config.max_tool_iterations == 0 {
        return Err("maxToolIterations must be greater than zero".to_string());
    }
    let mut names = std::collections::HashSet::new();
    for tool in &config.tools {
        if tool.name.trim().is_empty() || !names.insert(tool.name.as_str()) {
            return Err("tool names must be non-empty and unique".to_string());
        }
        if tool.description.trim().is_empty() {
            return Err(format!("tool {} description is required", tool.name));
        }
        if !tool.parameters.is_object() {
            return Err(format!(
                "tool {} parameters must be a JSON object",
                tool.name
            ));
        }
        if tool.parameters.get("type").and_then(Value::as_str) != Some("object") {
            return Err(format!(
                "tool {} parameters must be an object JSON Schema",
                tool.name
            ));
        }
    }
    Ok(())
}

fn start_session(config: SessionConfig) -> Result<(Arc<SessionController>, OpenedSession), String> {
    validate_config(&config)?;
    let (command_tx, command_rx) = mpsc::channel();
    let controller = Arc::new(SessionController {
        chat_id: config.chat_id.clone(),
        command_tx,
        abort: Mutex::new(None),
        busy: AtomicBool::new(false),
        cancelled: AtomicBool::new(false),
        api_key: config.api_key.clone(),
    });
    let thread_controller = Arc::clone(&controller);
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);
    std::thread::Builder::new()
        .name(format!("pi-{}", config.chat_id))
        .spawn(move || run_session_thread(config, thread_controller, command_rx, ready_tx))
        .map_err(|error| error.to_string())?;
    let opened = ready_rx.recv().map_err(|error| error.to_string())??;
    Ok((controller, opened))
}

fn run_session_thread(
    config: SessionConfig,
    controller: Arc<SessionController>,
    command_rx: mpsc::Receiver<SessionCommand>,
    ready_tx: mpsc::SyncSender<Result<OpenedSession, String>>,
) {
    let runtime = match create_reactor()
        .map_err(|error| error.to_string())
        .and_then(|reactor| {
            RuntimeBuilder::current_thread()
                .with_reactor(reactor)
                .build()
                .map_err(|error| error.to_string())
        }) {
        Ok(runtime) => runtime,
        Err(error) => {
            let _ = ready_tx.send(Err(error));
            return;
        }
    };
    let (mut handle, opened) =
        match runtime.block_on(build_agent_session(&config, runtime.handle())) {
            Ok(result) => result,
            Err(error) => {
                let _ = ready_tx.send(Err(redact_secret(&error, &config.api_key)));
                return;
            }
        };
    if ready_tx.send(Ok(opened)).is_err() {
        return;
    }

    while let Ok(command) = command_rx.recv() {
        match command {
            SessionCommand::Prompt { prompt, request_id } => {
                controller.cancelled.store(false, Ordering::Release);
                active_requests()
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .insert(controller.chat_id.clone(), request_id.clone());
                let (abort_handle, abort_signal) = AgentSessionHandle::new_abort_handle();
                *controller
                    .abort
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(abort_handle);
                let chat_id = controller.chat_id.clone();
                let event_request_id = request_id.clone();
                let api_key = controller.api_key.clone();
                let saw_agent_end = Arc::new(AtomicBool::new(false));
                let callback_saw_end = Arc::clone(&saw_agent_end);
                let result = runtime.block_on(handle.prompt_with_abort(
                    prompt,
                    abort_signal,
                    move |event| {
                        emit_agent_event(
                            &chat_id,
                            &event_request_id,
                            &api_key,
                            &callback_saw_end,
                            event,
                        );
                    },
                ));
                *controller
                    .abort
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
                active_requests()
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .remove(&controller.chat_id);
                controller.busy.store(false, Ordering::Release);

                if controller.cancelled.load(Ordering::Acquire) {
                    push_event(json!({
                        "type": "prompt_cancelled",
                        "chatId": controller.chat_id,
                        "requestId": request_id,
                    }));
                } else {
                    match result {
                        Ok(message) => push_event(json!({
                            "type": "prompt_completed",
                            "chatId": controller.chat_id,
                            "requestId": request_id,
                            "message": message,
                        })),
                        Err(error) => {
                            let message = redact_secret(&error.to_string(), &controller.api_key);
                            push_event(json!({
                                "type": "error",
                                "chatId": controller.chat_id,
                                "requestId": request_id,
                                "message": message,
                            }));
                            push_event(json!({
                                "type": "prompt_failed",
                                "chatId": controller.chat_id,
                                "requestId": request_id,
                                "message": message,
                            }));
                        }
                    }
                }
                if !saw_agent_end.load(Ordering::Acquire) {
                    push_event(json!({
                        "type": "agent_end",
                        "chatId": controller.chat_id,
                        "requestId": request_id,
                    }));
                }
            }
            SessionCommand::Compact {
                instructions,
                request_id,
            } => {
                controller.cancelled.store(false, Ordering::Release);
                active_requests()
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .insert(controller.chat_id.clone(), request_id.clone());
                let (abort_handle, abort_signal) = AgentSessionHandle::new_abort_handle();
                *controller
                    .abort
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(abort_handle);
                let chat_id = controller.chat_id.clone();
                let api_key = controller.api_key.clone();
                let event_request_id = request_id.clone();
                let previous_system_prompt =
                    handle.session().agent.system_prompt().map(str::to_string);
                if !instructions.trim().is_empty() {
                    let system_prompt = previous_system_prompt.as_deref().map_or_else(
                        || instructions.clone(),
                        |current| format!("{current}\n\nCompaction instructions:\n{instructions}"),
                    );
                    handle
                        .session_mut()
                        .agent
                        .set_system_prompt(Some(system_prompt));
                }
                let result =
                    runtime.block_on(handle.compact_with_abort(abort_signal, move |event| {
                        emit_compaction_event(&chat_id, &event_request_id, &api_key, event);
                    }));
                handle
                    .session_mut()
                    .agent
                    .set_system_prompt(previous_system_prompt);
                *controller
                    .abort
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
                active_requests()
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .remove(&controller.chat_id);
                controller.busy.store(false, Ordering::Release);
                match result {
                    Ok(()) => push_event(json!({
                        "type": "compact_completed",
                        "chatId": controller.chat_id,
                        "requestId": request_id,
                    })),
                    Err(pi::sdk::Error::Aborted) => {}
                    Err(error) => push_event(json!({
                        "type": "error",
                        "chatId": controller.chat_id,
                        "requestId": request_id,
                        "message": redact_secret(&error.to_string(), &controller.api_key),
                    })),
                }
            }
            SessionCommand::Close => break,
        }
    }
}

async fn build_agent_session(
    config: &SessionConfig,
    runtime_handle: RuntimeHandle,
) -> Result<(AgentSessionHandle, OpenedSession), String> {
    std::fs::create_dir_all(&config.working_directory).map_err(|error| error.to_string())?;
    let working_directory = PathBuf::from(&config.working_directory);
    let thinking = config
        .thinking_level
        .as_deref()
        .map(str::parse)
        .transpose()?;
    let max_tokens = config.max_tokens.unwrap_or(16_384);
    let model = Model {
        id: config.model.clone(),
        name: config.model.clone(),
        api: config.api.clone(),
        provider: config.provider.clone(),
        base_url: config.base_url.clone(),
        reasoning: config.reasoning
            || thinking.is_some_and(|level| level != pi::model::ThinkingLevel::Off),
        input: vec![InputType::Text, InputType::Image],
        cost: ModelCost {
            input: 0.0,
            output: 0.0,
            cache_read: 0.0,
            cache_write: 0.0,
        },
        context_window: config.context_window,
        max_tokens,
        headers: config.headers.clone(),
    };
    let entry = ModelEntry {
        model: model.clone(),
        api_key: (!config.api_key.trim().is_empty()).then(|| config.api_key.clone()),
        headers: config.headers.clone(),
        auth_header: true,
        compat: None,
        oauth_config: None,
    };
    let provider: Arc<dyn Provider> = pi::providers::create_provider(&entry, None)
        .map_err(|error| format!("provider creation failed: {error}"))?;
    let stream_options = StreamOptions {
        temperature: config.temperature,
        max_tokens: Some(max_tokens),
        api_key: (!config.api_key.trim().is_empty()).then(|| config.api_key.clone()),
        headers: config.headers.clone(),
        thinking_level: thinking,
        ..StreamOptions::default()
    };
    let tools = ToolRegistry::from_tools(
        config
            .tools
            .iter()
            .cloned()
            .map(|spec| {
                Box::new(AndroidToolProxy {
                    chat_id: config.chat_id.clone(),
                    spec,
                }) as Box<dyn Tool>
            })
            .collect(),
    );
    let agent = Agent::new(
        provider,
        tools,
        AgentConfig {
            system_prompt: Some(config.system_prompt.clone()),
            max_tool_iterations: config.max_tool_iterations,
            stream_options,
            block_images: false,
            fail_closed_hooks: true,
            tool_approval: None,
        },
    );

    let session_path = config
        .session_path
        .as_deref()
        .filter(|path| !path.is_empty());
    let mut session = match session_path {
        Some(path) if Path::new(path).exists() => pi::sdk::Session::open(path)
            .await
            .map_err(|error| error.to_string())?,
        Some(path) => {
            let path = PathBuf::from(path);
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent).map_err(|error| error.to_string())?;
            }
            let mut created =
                pi::sdk::Session::create_with_dir(path.parent().map(std::path::Path::to_path_buf));
            created.path = Some(path);
            created
        }
        None => {
            let directory = config
                .session_dir
                .as_deref()
                .filter(|path| !path.is_empty())
                .map(PathBuf::from);
            if let Some(path) = &directory {
                std::fs::create_dir_all(path).map_err(|error| error.to_string())?;
            }
            pi::sdk::Session::create_with_dir(directory)
        }
    };
    let previous_model = session.effective_model_for_current_path();
    session.header.cwd = working_directory.display().to_string();
    session.header.provider = Some(config.provider.clone());
    session.header.model_id = Some(config.model.clone());
    session.header.thinking_level = thinking.map(|level| level.to_string());
    if !session.entries.is_empty()
        && previous_model.as_ref() != Some(&(config.provider.clone(), config.model.clone()))
    {
        session.append_model_change(config.provider.clone(), config.model.clone());
    } else if session.entries.is_empty() {
        for message in decode_history(&config.history, config)? {
            session.append_model_message(message);
        }
    }
    session.save().await.map_err(|error| error.to_string())?;
    let history = session.to_messages_for_current_path();
    let opened = OpenedSession {
        session_id: session.header.id.clone(),
        session_path: session
            .path
            .as_ref()
            .map(|path| path.to_string_lossy().into_owned()),
        provider: config.provider.clone(),
        model: config.model.clone(),
        message_count: history.len(),
    };
    let session = Arc::new(asupersync::sync::Mutex::new(session));
    let mut agent_session = AgentSession::new(
        agent,
        session,
        true,
        ResolvedCompactionSettings {
            enabled: config.enable_compaction,
            context_window_tokens: config.context_window,
            reserve_tokens: config
                .compaction_reserve_tokens
                .unwrap_or_else(|| (config.context_window / 12).max(1024)),
            keep_recent_tokens: config
                .compaction_keep_recent_tokens
                .unwrap_or_else(|| (config.context_window / 10).max(4096)),
        },
    );
    agent_session = agent_session.with_runtime_handle(runtime_handle);
    agent_session.agent.replace_messages(history);
    Ok((
        AgentSessionHandle::from_session_with_listeners(agent_session, EventListeners::default()),
        opened,
    ))
}

fn decode_history(history: &Value, config: &SessionConfig) -> Result<Vec<Message>, String> {
    let value = match history {
        Value::Null => return Ok(Vec::new()),
        Value::String(raw) => serde_json::from_str(raw).map_err(|error| error.to_string())?,
        other => other.clone(),
    };
    let items = value
        .as_array()
        .ok_or_else(|| "history must be a JSON array".to_string())?;
    items
        .iter()
        .map(|item| decode_history_message(item, config))
        .collect()
}

fn decode_history_message(value: &Value, config: &SessionConfig) -> Result<Message, String> {
    if let Ok(message) = serde_json::from_value::<Message>(value.clone()) {
        return Ok(message);
    }
    let role = value
        .get("role")
        .and_then(Value::as_str)
        .ok_or_else(|| "history message role is required".to_string())?;
    let content = value
        .get("content")
        .and_then(Value::as_str)
        .ok_or_else(|| "history message content must be text".to_string())?;
    let timestamp = value
        .get("timestamp")
        .and_then(Value::as_i64)
        .unwrap_or_else(timestamp_ms);
    match role {
        "user" => Ok(Message::User(UserMessage {
            content: UserContent::Text(content.to_string()),
            timestamp,
        })),
        "assistant" => Ok(Message::assistant(AssistantMessage {
            content: vec![ContentBlock::Text(TextContent::new(content))],
            api: config.api.clone(),
            provider: config.provider.clone(),
            model: config.model.clone(),
            usage: Usage::default(),
            stop_reason: StopReason::Stop,
            error_message: None,
            timestamp,
        })),
        other => Err(format!("unsupported history message role: {other}")),
    }
}

fn emit_agent_event(
    chat_id: &str,
    request_id: &str,
    api_key: &str,
    saw_agent_end: &AtomicBool,
    event: AgentEvent,
) {
    match event {
        AgentEvent::MessageUpdate {
            assistant_message_event,
            ..
        } => match assistant_message_event {
            AssistantMessageEvent::TextDelta { delta, .. } => push_event(json!({
                "type": "text_delta", "chatId": chat_id, "requestId": request_id, "delta": delta,
            })),
            AssistantMessageEvent::ThinkingDelta { delta, .. } => push_event(json!({
                "type": "thinking_delta", "chatId": chat_id, "requestId": request_id, "delta": delta,
            })),
            AssistantMessageEvent::Error { error, .. } => {
                let message = error
                    .error_message
                    .as_deref()
                    .map(|message| redact_secret(message, api_key))
                    .unwrap_or_else(|| "Provider stream failed".to_string());
                push_event(json!({
                    "type": "error", "chatId": chat_id, "requestId": request_id, "message": message,
                }));
            }
            _ => {}
        },
        AgentEvent::ToolExecutionStart {
            tool_call_id,
            tool_name,
            args,
        } => {
            let host_request_id =
                bridge_android_tool_request_id(chat_id, request_id, &tool_call_id);
            push_event(json!({
                "type": "tool_start", "chatId": chat_id, "requestId": request_id,
                "toolCallId": host_request_id, "hostRequestId": host_request_id,
                "providerToolCallId": tool_call_id, "toolName": tool_name, "args": args,
            }));
        }
        AgentEvent::ToolExecutionUpdate {
            tool_call_id,
            tool_name,
            partial_result,
            ..
        } => {
            let host_request_id =
                bridge_android_tool_request_id(chat_id, request_id, &tool_call_id);
            push_event(json!({
                "type": "tool_update", "chatId": chat_id, "requestId": request_id,
                "toolCallId": host_request_id, "hostRequestId": host_request_id,
                "providerToolCallId": tool_call_id, "toolName": tool_name,
                "content": text_from_blocks(&partial_result.content),
                "details": partial_result.details,
            }));
        }
        AgentEvent::ToolExecutionEnd {
            tool_call_id,
            tool_name,
            result,
            is_error,
        } => {
            let host_request_id =
                bridge_android_tool_request_id(chat_id, request_id, &tool_call_id);
            push_event(json!({
                "type": "tool_end", "chatId": chat_id, "requestId": request_id,
                "toolCallId": host_request_id, "hostRequestId": host_request_id,
                "providerToolCallId": tool_call_id, "toolName": tool_name,
                "content": text_from_blocks(&result.content), "details": result.details, "isError": is_error,
            }));
        }
        AgentEvent::AutoCompactionStart { reason } => {
            push_auto_compaction_start(chat_id, request_id, &reason);
        }
        AgentEvent::AutoCompactionEnd {
            error_message,
            aborted,
            ..
        } => {
            push_auto_compaction_end(chat_id, request_id, api_key, aborted, error_message);
        }
        AgentEvent::AgentEnd {
            messages, error, ..
        } => {
            if let Some(assistant) = messages.iter().rev().find_map(|message| match message {
                Message::Assistant(assistant) => Some(assistant),
                _ => None,
            }) {
                push_event(json!({
                    "type": "usage", "chatId": chat_id, "requestId": request_id,
                    "inputTokens": assistant.usage.input,
                    "outputTokens": assistant.usage.output,
                    "cachedInputTokens": assistant.usage.cache_read,
                    "totalTokens": assistant.usage.total_tokens,
                }));
            }
            if let Some(error) = error {
                push_event(json!({
                    "type": "error", "chatId": chat_id, "requestId": request_id,
                    "message": redact_secret(&error, api_key),
                }));
            }
            saw_agent_end.store(true, Ordering::Release);
            push_event(json!({
                "type": "agent_end", "chatId": chat_id, "requestId": request_id,
            }));
        }
        _ => {}
    }
}

fn push_auto_compaction_start(chat_id: &str, request_id: &str, reason: &str) {
    push_event(json!({
        "type": "auto_compaction_start",
        "chatId": chat_id,
        "requestId": request_id,
        "reason": reason,
    }));
}

fn push_auto_compaction_end(
    chat_id: &str,
    request_id: &str,
    api_key: &str,
    aborted: bool,
    error_message: Option<String>,
) {
    let error = error_message
        .map(|error| redact_secret(&error, api_key))
        .or_else(|| aborted.then(|| "Compaction cancelled".to_string()));
    push_event(json!({
        "type": "auto_compaction_end",
        "chatId": chat_id,
        "requestId": request_id,
        "success": !aborted && error.is_none(),
        "error": error,
    }));
}

fn emit_compaction_event(chat_id: &str, request_id: &str, api_key: &str, event: AgentEvent) {
    match event {
        AgentEvent::AutoCompactionStart { reason } => {
            push_auto_compaction_start(chat_id, request_id, &reason);
        }
        AgentEvent::AutoCompactionEnd {
            error_message,
            aborted,
            ..
        } => {
            push_auto_compaction_end(chat_id, request_id, api_key, aborted, error_message);
        }
        _ => {}
    }
}

fn text_from_blocks(blocks: &[ContentBlock]) -> String {
    blocks
        .iter()
        .filter_map(|block| match block {
            ContentBlock::Text(text) => Some(text.text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("")
}

fn parse_android_tool_completion(raw: &str) -> Result<AndroidToolCompletion, String> {
    let value: Value = serde_json::from_str(raw).map_err(|error| error.to_string())?;
    let is_error = value
        .get("isError")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let content_value = value
        .get("content")
        .ok_or_else(|| "Android tool result content is required".to_string())?;
    let content = if let Some(text) = content_value.as_str() {
        let text = if is_error && text.trim().is_empty() {
            value
                .get("error")
                .and_then(Value::as_str)
                .filter(|error| !error.trim().is_empty())
                .unwrap_or("Android tool failed")
        } else {
            text
        };
        vec![ContentBlock::Text(TextContent::new(text))]
    } else {
        serde_json::from_value::<Vec<ContentBlock>>(content_value.clone())
            .map_err(|error| error.to_string())?
    };
    Ok(AndroidToolCompletion {
        content,
        details: value.get("details").cloned(),
        is_error,
    })
}

fn cancel_session(controller: &SessionController) -> bool {
    controller.cancelled.store(true, Ordering::Release);
    let aborted = controller
        .abort
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .as_ref()
        .is_some_and(|handle| {
            handle.abort();
            true
        });
    let keys = {
        let pending = pending_tools()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        pending
            .iter()
            .filter(|(_, pending)| pending.chat_id == controller.chat_id)
            .map(|(key, _)| key.clone())
            .collect::<Vec<_>>()
    };
    let had_pending_tools = !keys.is_empty();
    for key in &keys {
        if let Some(pending) = pending_tools()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(key)
        {
            let _ = pending.sender.send(AndroidToolCompletion {
                content: vec![ContentBlock::Text(TextContent::new("Cancelled"))],
                details: Some(json!({"state": "cancelled"})),
                is_error: true,
            });
        }
    }
    aborted || had_pending_tools
}

fn cancel_all_pending(reason: &str) {
    let tools = {
        let mut pending = pending_tools()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        std::mem::take(&mut *pending)
    };
    for (_, pending) in tools {
        let _ = pending.sender.send(AndroidToolCompletion {
            content: vec![ContentBlock::Text(TextContent::new(reason))],
            details: Some(json!({"state": "cancelled", "reason": reason})),
            is_error: true,
        });
    }
}

fn redact_secret(message: &str, secret: &str) -> String {
    if secret.is_empty() {
        message.to_string()
    } else {
        message.replace(secret, "[REDACTED]")
    }
}

fn timestamp_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| i64::try_from(duration.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or_default()
}

fn rust_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|error| error.to_string())
}

fn java_string(env: &mut JNIEnv<'_>, value: impl AsRef<str>) -> jstring {
    env.new_string(value.as_ref())
        .map(jni::objects::JString::into_raw)
        .unwrap_or(ptr::null_mut())
}

fn json_response(env: &mut JNIEnv<'_>, value: Value) -> jstring {
    java_string(env, value.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativeOpenSession(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config_json: JString<'_>,
) -> jstring {
    let config_raw = match rust_string(&mut env, &config_json) {
        Ok(value) => value,
        Err(error) => return json_response(&mut env, json!({"ok": false, "error": error})),
    };
    let config: SessionConfig = match serde_json::from_str(&config_raw) {
        Ok(value) => value,
        Err(error) => {
            return json_response(
                &mut env,
                json!({"ok": false, "error": format!("invalid config: {error}")}),
            );
        }
    };
    let existing = sessions()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .get(&config.chat_id)
        .cloned();
    if let Some(existing) = existing {
        if existing.busy.load(Ordering::Acquire) {
            return json_response(&mut env, json!({"ok": false, "error": "session is busy"}));
        }
        sessions()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(&config.chat_id);
        cancel_session(&existing);
        let _ = existing.command_tx.send(SessionCommand::Close);
    }
    let api_key = config.api_key.clone();
    match start_session(config) {
        Ok((controller, opened)) => {
            let chat_id = controller.chat_id.clone();
            sessions()
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .insert(chat_id.clone(), controller);
            json_response(
                &mut env,
                json!({
                    "ok": true,
                    "chatId": chat_id,
                    "sessionId": opened.session_id,
                    "sessionPath": opened.session_path,
                    "provider": opened.provider,
                    "model": opened.model,
                    "messageCount": opened.message_count,
                }),
            )
        }
        Err(error) => json_response(
            &mut env,
            json!({"ok": false, "error": redact_secret(&error, &api_key)}),
        ),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativePrompt(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    chat_id: JString<'_>,
    prompt: JString<'_>,
    request_id: JString<'_>,
) -> jstring {
    let values = (
        rust_string(&mut env, &chat_id),
        rust_string(&mut env, &prompt),
        rust_string(&mut env, &request_id),
    );
    let (Ok(chat_id), Ok(prompt), Ok(request_id)) = values else {
        return json_response(
            &mut env,
            json!({"ok": false, "error": "invalid JNI string"}),
        );
    };
    let controller = sessions()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .get(&chat_id)
        .cloned();
    let Some(controller) = controller else {
        return json_response(
            &mut env,
            json!({"ok": false, "error": "chatId is not open"}),
        );
    };
    if controller
        .busy
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return json_response(&mut env, json!({"ok": false, "error": "session is busy"}));
    }
    if controller
        .command_tx
        .send(SessionCommand::Prompt {
            prompt,
            request_id: request_id.clone(),
        })
        .is_err()
    {
        controller.busy.store(false, Ordering::Release);
        return json_response(
            &mut env,
            json!({"ok": false, "error": "session worker stopped"}),
        );
    }
    json_response(
        &mut env,
        json!({"ok": true, "chatId": chat_id, "requestId": request_id}),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativeCompact(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    chat_id: JString<'_>,
    instructions: JString<'_>,
    request_id: JString<'_>,
) -> jstring {
    let values = (
        rust_string(&mut env, &chat_id),
        rust_string(&mut env, &instructions),
        rust_string(&mut env, &request_id),
    );
    let (Ok(chat_id), Ok(instructions), Ok(request_id)) = values else {
        return json_response(
            &mut env,
            json!({"ok": false, "error": "invalid JNI string"}),
        );
    };
    if request_id.trim().is_empty() {
        return json_response(
            &mut env,
            json!({"ok": false, "error": "requestId is required"}),
        );
    }
    let controller = sessions()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .get(&chat_id)
        .cloned();
    let Some(controller) = controller else {
        return json_response(
            &mut env,
            json!({"ok": false, "error": "chatId is not open"}),
        );
    };
    if controller
        .busy
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return json_response(&mut env, json!({"ok": false, "error": "session is busy"}));
    }
    if controller
        .command_tx
        .send(SessionCommand::Compact {
            instructions,
            request_id: request_id.clone(),
        })
        .is_err()
    {
        controller.busy.store(false, Ordering::Release);
        return json_response(
            &mut env,
            json!({"ok": false, "error": "session worker stopped"}),
        );
    }
    json_response(
        &mut env,
        json!({"ok": true, "chatId": chat_id, "requestId": request_id}),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativeCancel(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    chat_id: JString<'_>,
) -> jboolean {
    let Ok(chat_id) = rust_string(&mut env, &chat_id) else {
        return 0;
    };
    sessions()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .get(&chat_id)
        .is_some_and(|controller| cancel_session(controller)) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativeCompleteHostTool(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: JString<'_>,
    result_json: JString<'_>,
) -> jboolean {
    let Ok(request_id) = rust_string(&mut env, &request_id) else {
        return 0;
    };
    let Ok(result_json) = rust_string(&mut env, &result_json) else {
        return 0;
    };
    let Ok(completion) = parse_android_tool_completion(&result_json) else {
        return 0;
    };
    pending_tools()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .remove(&request_id)
        .is_some_and(|pending| pending.sender.send(completion).is_ok()) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativePollEvents(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    max_events: jint,
) -> jstring {
    let maximum = usize::try_from(max_events.max(0)).unwrap_or_default();
    let events = event_queue()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .drain(maximum);
    json_response(&mut env, Value::Array(events))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ai_assistance_operit_pi_RescueNativeBridge_nativeCloseSession(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    chat_id: JString<'_>,
) -> jboolean {
    let Ok(chat_id) = rust_string(&mut env, &chat_id) else {
        return 0;
    };
    let controller = sessions()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .remove(&chat_id);
    controller.is_some_and(|controller| {
        cancel_session(&controller);
        controller.command_tx.send(SessionCommand::Close).is_ok()
    }) as jboolean
}

#[cfg(test)]
mod tests {
    use super::*;

    fn direct_provider_config() -> SessionConfig {
        SessionConfig {
            chat_id: "chat-test".to_string(),
            provider: "deepseek".to_string(),
            api: "openai-completions".to_string(),
            model: "deepseek-chat".to_string(),
            base_url: "https://api.deepseek.com/v1/chat/completions".to_string(),
            api_key: "test-key".to_string(),
            headers: HashMap::new(),
            system_prompt: "You are helpful.".to_string(),
            max_tokens: Some(4096),
            temperature: Some(0.25),
            context_window: 128_000,
            thinking_level: Some("off".to_string()),
            tools: vec![AndroidToolSpec {
                name: "read_file".to_string(),
                label: "Read file".to_string(),
                description: "Read an Android-accessible file".to_string(),
                parameters: json!({
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                    "required": ["path"],
                    "additionalProperties": false,
                }),
            }],
            history: Value::Array(Vec::new()),
            session_path: None,
            session_dir: None,
            working_directory: "/tmp".to_string(),
            max_tool_iterations: 32,
            reasoning: false,
            enable_compaction: true,
            compaction_reserve_tokens: Some(16_000),
            compaction_keep_recent_tokens: Some(8_000),
        }
    }

    struct PendingCompactionProvider;

    #[async_trait]
    impl Provider for PendingCompactionProvider {
        fn name(&self) -> &str {
            "test-provider"
        }

        fn api(&self) -> &str {
            "test-api"
        }

        fn model_id(&self) -> &str {
            "test-model"
        }

        async fn stream(
            &self,
            _context: &ProviderContext<'_>,
            _options: &StreamOptions,
        ) -> pi::sdk::Result<Pin<Box<dyn Stream<Item = pi::sdk::Result<StreamEvent>> + Send>>>
        {
            futures::future::pending().await
        }
    }

    #[test]
    fn event_queue_is_bounded_and_reports_drops() {
        let mut queue = EventQueue::default();
        for index in 0..=EVENT_QUEUE_CAPACITY {
            queue.push(json!({"index": index}));
        }
        assert_eq!(queue.values.len(), EVENT_QUEUE_CAPACITY);
        let events = queue.drain(2);
        assert_eq!(events[0]["type"], "event_queue_overflow");
        assert_eq!(events[0]["dropped"], 1);
    }

    #[test]
    fn direct_provider_configuration_is_accepted() {
        validate_config(&direct_provider_config()).expect("direct provider config");
    }

    #[test]
    fn operit_host_configuration_is_rejected() {
        let mut config = direct_provider_config();
        config.api = "operit-host".to_string();
        let error = validate_config(&config).expect_err("operit-host must be rejected");
        assert!(error.contains("not a supported Pi provider"));
    }

    #[test]
    fn tool_catalog_requires_object_json_schema() {
        let mut config = direct_provider_config();
        config.tools[0].parameters = json!({"type": "string"});
        let error = validate_config(&config).expect_err("invalid tool schema");
        assert!(error.contains("object JSON Schema"));
    }

    #[test]
    fn parses_android_tool_text_result() {
        let result = parse_android_tool_completion(
            r#"{"content":"done","details":{"exitCode":0},"isError":false}"#,
        )
        .expect("parse result");
        assert_eq!(text_from_blocks(&result.content), "done");
        assert!(!result.is_error);
    }

    #[test]
    fn parses_android_tool_error_when_text_content_is_blank() {
        let result = parse_android_tool_completion(
            r#"{"content":"  ","error":"command failed","isError":true}"#,
        )
        .expect("parse result");
        assert_eq!(text_from_blocks(&result.content), "command failed");
        assert!(result.is_error);
    }

    #[test]
    fn falls_back_when_android_tool_error_and_text_content_are_blank() {
        let result = parse_android_tool_completion(r#"{"content":"","error":"  ","isError":true}"#)
            .expect("parse result");
        assert_eq!(text_from_blocks(&result.content), "Android tool failed");
        assert!(result.is_error);
    }

    #[test]
    fn redacts_api_key() {
        assert_eq!(
            redact_secret("request with secret-key failed", "secret-key"),
            "request with [REDACTED] failed"
        );
    }

    #[test]
    fn android_tool_ids_are_namespaced_by_session_and_turn() {
        let first = bridge_android_tool_request_id("chat-a", "turn-1", "provider-call");
        let second = bridge_android_tool_request_id("chat-b", "turn-1", "provider-call");
        let third = bridge_android_tool_request_id("chat-a", "turn-2", "provider-call");
        assert_ne!(first, second);
        assert_ne!(first, third);
        assert_eq!(
            first,
            bridge_android_tool_request_id("chat-a", "turn-1", "provider-call")
        );
    }

    #[test]
    fn manual_compaction_observes_abort_signal() {
        let long_text = "x".repeat(100_000);
        let mut session = pi::sdk::Session::in_memory();
        session.header.provider = Some("test-provider".to_string());
        session.header.model_id = Some("test-model".to_string());
        for index in 0..5 {
            session.append_model_message(Message::User(UserMessage {
                content: UserContent::Text(format!("{index}:{long_text}")),
                timestamp: i64::from(index),
            }));
        }
        let agent = Agent::new(
            Arc::new(PendingCompactionProvider),
            ToolRegistry::from_tools(Vec::new()),
            AgentConfig {
                system_prompt: None,
                max_tool_iterations: 1,
                stream_options: StreamOptions::default(),
                block_images: false,
                fail_closed_hooks: true,
                tool_approval: None,
            },
        );
        let session = Arc::new(asupersync::sync::Mutex::new(session));
        let agent_session = AgentSession::new(
            agent,
            session,
            false,
            ResolvedCompactionSettings {
                enabled: true,
                context_window_tokens: 100_000,
                reserve_tokens: 1_000,
                keep_recent_tokens: 100,
            },
        );
        let mut handle = AgentSessionHandle::from_session_with_listeners(
            agent_session,
            EventListeners::default(),
        );
        let (abort_handle, abort_signal) = AgentSessionHandle::new_abort_handle();
        abort_handle.abort();
        let saw_aborted_end = Arc::new(AtomicBool::new(false));
        let callback_flag = Arc::clone(&saw_aborted_end);
        let reactor = create_reactor().expect("reactor");
        let runtime = RuntimeBuilder::current_thread()
            .with_reactor(reactor)
            .build()
            .expect("runtime");
        let result = runtime.block_on(handle.compact_with_abort(abort_signal, move |event| {
            if matches!(event, AgentEvent::AutoCompactionEnd { aborted: true, .. }) {
                callback_flag.store(true, Ordering::Release);
            }
        }));
        assert!(matches!(result, Err(pi::sdk::Error::Aborted)));
        assert!(saw_aborted_end.load(Ordering::Acquire));
    }
}
