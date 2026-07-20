"use client";

import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import type { AgentMessage, ExtensionUiRequest, SessionInfo, SessionTreeNode } from "@/lib/types";
import type { AssistantSummary, AssistantTtsConfig, PermissionDecision, PermissionRequest, WebExtensionSummary } from "@/lib/wuxianpi/contracts";
import { ChatInput, type ChatInputHandle } from "./ChatInput";
import { useAgentSession, type AgentPhase, type NoticeItem } from "@/hooks/useAgentSession";
import { useAudio } from "@/hooks/useAudio";
import { useTts } from "@/hooks/useTts";
import { useDragDrop } from "@/hooks/useDragDrop";
import { STARTER_PROMPTS } from "@/lib/starter-prompts";
import type { SessionStatsInfo } from "@/lib/pi-types";

const MessageView = lazy(() => import("./MessageView").then((module) => ({ default: module.MessageView })));

interface Props {
  assistantId?: string;
  assistant?: AssistantSummary;
  webExtensions?: WebExtensionSummary[];
  defaultTts?: AssistantTtsConfig;
  session: SessionInfo | null;
  newSessionCwd: string | null;
  onAgentEnd?: () => void;
  onSessionCreated?: (session: SessionInfo) => void;
  onSessionForked?: (newSessionId: string) => void;
  modelsRefreshKey?: number;
  chatInputRef?: React.RefObject<ChatInputHandle | null>;
  onBranchDataChange?: (tree: SessionTreeNode[], activeLeafId: string | null, onLeafChange: (leafId: string | null) => void) => void;
  onSystemPromptChange?: (prompt: string | null) => void;
  onSessionStatsChange?: (stats: SessionStatsInfo | null) => void;
  onSessionStatsPanelOpen?: () => void;
  onOpenModelsConfig?: () => void;
  onContextUsageChange?: (usage: { percent: number | null; contextWindow: number; tokens: number | null } | null) => void;
  initialPrompt?: string | null;
  initialPromptKey?: string | null;
  onInitialPromptQueued?: () => void;
}

function phaseLabel(phase: AgentPhase): string {
  if (phase?.kind === "running_tools") {
    const names = phase.tools.map((t) => t.name);
    if (names.length === 0) return "正在运行工具...";
    if (names.length === 1) return `正在运行 ${names[0]}...`;
    if (names.length <= 3) return `正在运行 ${names.join(", ")}...`;
    return `正在运行 ${names.slice(0, 2).join(", ")}（另 ${names.length - 2} 个）...`;
  }
  if (phase?.kind === "waiting_model") return "等待模型回复...";
  if (phase?.kind === "running_command") return "正在执行命令...";
  return "正在思考...";
}

const TYPEWRITER_PHRASES = [
  "直接问，也可以先选一个任务。",
  "帮你读代码、改代码、跑验证。",
  "把项目里的复杂问题说清楚。",
  "浏览并理解整个代码库。",
  "一起完成一次改动。",
  "定位并修复一个问题。",
  "审查当前改动风险。",
  "把功能做到可验证。",
];

const CHAT_COLUMN_PADDING = 16;

function Typewriter({ phrases }: { phrases: string[] }) {
  const [phraseIdx, setPhraseIdx] = useState(() => Math.floor(Math.random() * phrases.length));
  const [text, setText] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [caretOn, setCaretOn] = useState(true);

  useEffect(() => {
    const blink = setInterval(() => setCaretOn((v) => !v), 530);
    return () => clearInterval(blink);
  }, []);

  useEffect(() => {
    const current = phrases[phraseIdx];
    let timeout: ReturnType<typeof setTimeout>;
    if (!deleting && text === current) {
      timeout = setTimeout(() => setDeleting(true), 1800);
    } else if (deleting && text === "") {
      setDeleting(false);
      setPhraseIdx((i) => (i + 1) % phrases.length);
    } else {
      const next = deleting ? current.slice(0, text.length - 1) : current.slice(0, text.length + 1);
      timeout = setTimeout(() => setText(next), deleting ? 28 : 55);
    }
    return () => clearTimeout(timeout);
  }, [text, deleting, phraseIdx, phrases]);

  return (
    <span style={{ color: "var(--text-muted)", fontWeight: 400 }}>
      {text}
      <span style={{ opacity: caretOn ? 1 : 0, color: "var(--accent)", marginLeft: 1 }}>▍</span>
    </span>
  );
}

export function ChatWindow({ assistantId, assistant, webExtensions = [], defaultTts, session, newSessionCwd, onAgentEnd, onSessionCreated, onSessionForked, modelsRefreshKey, chatInputRef, onBranchDataChange, onSystemPromptChange, onSessionStatsChange, onSessionStatsPanelOpen, onOpenModelsConfig, onContextUsageChange, initialPrompt, initialPromptKey, onInitialPromptQueued }: Props) {
  const { soundEnabled, onSoundToggle, playDoneSound } = useAudio();
  const assistantTts = assistant?.manifest.tts;
  const ttsConfig = !assistantTts || assistantTts === "inherit" ? defaultTts : assistantTts;
  const { speak, stop: stopSpeaking, speaking, error: ttsError } = useTts(assistantId, ttsConfig);
  const autoSpeakPendingRef = useRef(false);
  const playDoneSoundRef = useRef(playDoneSound);
  playDoneSoundRef.current = playDoneSound;
  const soundEnabledRef = useRef(soundEnabled);
  soundEnabledRef.current = soundEnabled;
  const handleAgentCompleted = useCallback(() => {
    if (soundEnabledRef.current) playDoneSoundRef.current();
    autoSpeakPendingRef.current = true;
    onAgentEnd?.();
  }, [onAgentEnd]);

  const {
    loading, error, messages, entryIds, streamState,
    agentRunning, modelNames, modelList, modelThinkingLevels, modelThinkingLevelMaps, toolPreset, thinkingLevel,
    retryInfo, contextUsage, forkingEntryId,
    isCompacting, compactError, compactResult, displayModel: displayModelValue, sessionStats,
    slashCommands, slashCommandsLoading,
    notices, extensionDialog, extensionStatuses, extensionWidgets, respondToExtensionUi,
    permissionRequest, respondToPermission,
    isAutoModelSelection,
    agentPhase,
    isNew,
    messagesEndRef, scrollContainerRef,
    lastUserMsgRef,
    handleSend, handleAbort, handleFork, handleNavigate, handleModelChange,
    handleCompact, handleSteer, handleFollowUp, handlePromptWithStreamingBehavior, handleAbortCompaction,
    handleBuiltinSlashCommand,
    handleToolPresetChange, handleThinkingLevelChange, loadSlashCommands,
  } = useAgentSession({
    assistantId, session, newSessionCwd, onAgentEnd: handleAgentCompleted, onSessionCreated, onSessionForked,
    modelsRefreshKey, onBranchDataChange, onSystemPromptChange, onSessionStatsPanelOpen,
  });

  // Push session stats up to AppShell for the top bar.
  // Compare scalar fields to avoid loops from new object identity each render.
  const statsKey = sessionStats
    ? [
      sessionStats.sessionId,
      sessionStats.sessionFile ?? "",
      sessionStats.sessionName ?? "",
      sessionStats.userMessages,
      sessionStats.assistantMessages,
      sessionStats.toolCalls,
      sessionStats.toolResults,
      sessionStats.totalMessages,
      sessionStats.tokens.input,
      sessionStats.tokens.output,
      sessionStats.tokens.cacheRead,
      sessionStats.tokens.cacheWrite,
      sessionStats.tokens.total,
      sessionStats.cost ?? 0,
    ].join("|")
    : null;
  const sessionStatsRef = useRef(sessionStats);
  sessionStatsRef.current = sessionStats;
  useEffect(() => {
    onSessionStatsChange?.(sessionStatsRef.current);
  }, [statsKey, onSessionStatsChange]);
  useEffect(() => () => { onSessionStatsChange?.(null); }, [onSessionStatsChange]);

  // Push context usage up to AppShell as well.
  const ctxKey = contextUsage
    ? `${contextUsage.percent ?? "null"}|${contextUsage.contextWindow}|${contextUsage.tokens ?? "null"}`
    : null;
  const contextUsageRef = useRef(contextUsage);
  contextUsageRef.current = contextUsage;
  useEffect(() => {
    onContextUsageChange?.(contextUsageRef.current);
  }, [ctxKey, onContextUsageChange]);
  useEffect(() => () => { onContextUsageChange?.(null); }, [onContextUsageChange]);

  const onDrop = useCallback((files: File[]) => {
    chatInputRef?.current?.addImages(files);
  }, [chatInputRef]);

  const { isDragOver, handleDragEnter, handleDragOver, handleDragLeave, handleDrop } = useDragDrop(onDrop);

  const rows = useMemo(() => messages.map((message, index) => ({ message, index })).filter(({ message }) => message.role !== "toolResult"), [messages]);
  const toolResultsMap = useMemo(() => {
    const map = new Map<string, import("@/lib/types").ToolResultMessage>();
    for (const message of messages) if (message.role === "toolResult") map.set(message.toolCallId, message);
    return map;
  }, [messages]);
  const lastUserIndex = useMemo(() => {
    for (let index = messages.length - 1; index >= 0; index -= 1) if (messages[index].role === "user") return index;
    return -1;
  }, [messages]);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: (index) => rows[index]?.message.role === "user" ? 96 : 180,
    overscan: 6,
  });
  const defaultFullToolsAppliedRef = useRef(false);
  const initialPromptSentKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!isNew || assistantId || defaultFullToolsAppliedRef.current) return;
    defaultFullToolsAppliedRef.current = true;
    if (toolPreset !== "full") void handleToolPresetChange("full");
  }, [assistantId, isNew, toolPreset, handleToolPresetChange]);

  const latestAssistantText = useMemo(() => {
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      const message = messages[index];
      if (message.role !== "assistant") continue;
      return message.content.filter((block): block is import("@/lib/types").TextContent => block.type === "text").map((block) => block.text).join("\n");
    }
    return "";
  }, [messages]);
  const autoSpokenRef = useRef("");
  useEffect(() => {
    if (!ttsConfig?.autoSpeak || !autoSpeakPendingRef.current || agentRunning || streamState.isStreaming || !latestAssistantText || autoSpokenRef.current === latestAssistantText) return;
    autoSpeakPendingRef.current = false;
    autoSpokenRef.current = latestAssistantText;
    void speak(latestAssistantText);
  }, [agentRunning, latestAssistantText, speak, streamState.isStreaming, ttsConfig]);

  const handleAbortWithTts = useCallback(() => {
    stopSpeaking();
    void handleAbort();
  }, [handleAbort, stopSpeaking]);

  const isEmptyNew = isNew && messages.length === 0 && !streamState.isStreaming && !agentRunning;
  const quickPrompts = assistant?.manifest.starterPrompts?.length
    ? assistant.manifest.starterPrompts.map((prompt) => ({ title: prompt, prompt, description: "开始这个话题" }))
    : STARTER_PROMPTS;
  const handleQuickStartPrompt = useCallback((prompt: string) => {
    chatInputRef?.current?.insertIfEmpty(prompt);
  }, [chatInputRef]);

  useEffect(() => {
    if (!initialPrompt || !initialPromptKey) return;
    if (initialPromptSentKeyRef.current === initialPromptKey) return;
    if (!isEmptyNew || agentRunning || streamState.isStreaming) return;
    if (!assistantId && toolPreset !== "full") return;

    initialPromptSentKeyRef.current = initialPromptKey;
    void handleSend(initialPrompt);
    onInitialPromptQueued?.();
  }, [
    agentRunning,
    assistantId,
    handleSend,
    initialPrompt,
    initialPromptKey,
    isEmptyNew,
    onInitialPromptQueued,
    streamState.isStreaming,
    toolPreset,
  ]);

  const availableThinkingLevels = displayModelValue
    ? (modelThinkingLevels[`${displayModelValue.provider}:${displayModelValue.modelId}`] ?? null)
    : null;

  const currentThinkingLevelMap = displayModelValue
    ? (modelThinkingLevelMaps[`${displayModelValue.provider}:${displayModelValue.modelId}`] ?? null)
    : null;

  const chatInputElement = (
    <ChatInput
      ref={chatInputRef}
      onSend={handleSend}
      onAbort={handleAbortWithTts}
      onSteer={agentRunning ? handleSteer : undefined}
      onFollowUp={agentRunning ? handleFollowUp : undefined}
      onPromptWithStreamingBehavior={agentRunning ? handlePromptWithStreamingBehavior : undefined}
      isStreaming={agentRunning}
      model={displayModelValue}
      isAutoModelSelection={isAutoModelSelection}
      modelNames={modelNames}
      modelList={modelList}
      onModelChange={handleModelChange}
      onOpenModelsConfig={onOpenModelsConfig}
      onCompact={session || isNew ? handleCompact : undefined}
      onAbortCompaction={handleAbortCompaction}
      isCompacting={isCompacting}
      compactError={compactError}
      compactResult={compactResult}
      toolPreset={toolPreset}
      onToolPresetChange={session || isNew ? handleToolPresetChange : undefined}
      thinkingLevel={thinkingLevel}
      onThinkingLevelChange={session || isNew ? handleThinkingLevelChange : undefined}
      availableThinkingLevels={availableThinkingLevels}
      thinkingLevelMap={currentThinkingLevelMap}
      retryInfo={retryInfo}
      slashCommands={slashCommands}
      slashCommandsLoading={slashCommandsLoading}
      onLoadSlashCommands={loadSlashCommands}
      onBuiltinCommand={handleBuiltinSlashCommand}
      soundEnabled={soundEnabled}
      onSoundToggle={onSoundToggle}
    />
  );

  const aboveEditorWidgets = extensionWidgets.filter((widget) => widget.placement !== "belowEditor");
  const belowEditorWidgets = extensionWidgets.filter((widget) => widget.placement === "belowEditor");

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center text-text-muted">
        正在加载会话...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full items-center justify-center text-red-400">
        {error}
      </div>
    );
  }

  return (
    <div
      className="relative flex h-full flex-col overflow-hidden"
      onDragEnter={handleDragEnter}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {isDragOver && (
        <div className="pointer-events-none absolute inset-0 z-50 flex animate-[drop-zone-in_0.15s_ease_both] items-center justify-center bg-[rgba(37,99,235,0.06)] backdrop-blur-[1px]">
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            {[0, 0.8, 1.6].map((delay) => (
              <div
                key={delay}
                className="absolute h-[720px] w-[720px] rounded-full border-[1.5px] border-solid border-[rgba(37,99,235,0.5)] animate-[drop-ripple_2.4s_ease-out_infinite_backwards]"
                style={{ transformOrigin: "center", animationDelay: `${delay}s` }}
              />
            ))}
          </div>
          <svg
            width="280" height="280" viewBox="0 0 140 140" fill="none" xmlns="http://www.w3.org/2000/svg"
            className="drop-shadow-[0_6px_18px_rgba(37,99,235,0.18)]"
          >
            <rect x="28" y="44" width="84" height="60" rx="8" fill="rgba(37,99,235,0.08)" stroke="rgba(37,99,235,0.50)" strokeWidth="1.8"/>
            <path d="M36 100 L54 72 L68 88 L80 74 L104 100Z" fill="rgba(37,99,235,0.16)" stroke="rgba(37,99,235,0.40)" strokeWidth="1.4" strokeLinejoin="round"/>
            <circle cx="96" cy="58" r="8" fill="rgba(37,99,235,0.22)" stroke="rgba(37,99,235,0.55)" strokeWidth="1.6"/>
            <g stroke="rgba(37,99,235,0.45)" strokeWidth="1.4" strokeLinecap="round">
              <line x1="96" y1="46" x2="96" y2="43"/>
              <line x1="96" y1="70" x2="96" y2="73"/>
              <line x1="84" y1="58" x2="81" y2="58"/>
              <line x1="108" y1="58" x2="111" y2="58"/>
              <line x1="87.5" y1="49.5" x2="85.4" y2="47.4"/>
              <line x1="104.5" y1="66.5" x2="106.6" y2="68.6"/>
              <line x1="104.5" y1="49.5" x2="106.6" y2="47.4"/>
              <line x1="87.5" y1="66.5" x2="85.4" y2="68.6"/>
            </g>
          </svg>
        </div>
      )}

      {extensionDialog && (
        <ExtensionDialog
          request={extensionDialog}
          onRespond={respondToExtensionUi}
        />
      )}

      {permissionRequest && (
        <PermissionDialog request={permissionRequest} onRespond={respondToPermission} />
      )}

      {isEmptyNew ? (
        <div className="flex flex-1 flex-col overflow-hidden">
          <div
            className="flex flex-1 items-center justify-center overflow-y-auto px-4"
            style={{
              paddingTop: 56,
              paddingBottom: 24,
            }}
          >
            <div className="w-full max-w-[860px]">
              <div
                style={{
                  display: "flex",
                  flexDirection: "column",
                  gap: 10,
                  paddingLeft: 16,
                  paddingRight: 52,
                }}
              >
                <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 12 }}>
                  <div style={{ display: "flex", alignItems: "flex-start", gap: 10, minWidth: 0, flex: 1 }}>
                    <span style={{ fontSize: 30, lineHeight: 1.2, fontWeight: 750, letterSpacing: 0, color: "var(--text)", flexShrink: 0 }}>∞π</span>
                    <h1 style={{ margin: 0, fontSize: 28, lineHeight: 1.25, color: "var(--text)", fontWeight: 720, letterSpacing: 0, overflow: "visible" }}>
                      {assistant?.manifest.greeting || `今天想和${assistant?.manifest.name ?? "助手"}聊什么？`}
                    </h1>
                  </div>
                  <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 2, flexShrink: 0, fontFamily: "var(--font-mono)" }}>
                    <span style={{ fontSize: 11, color: "var(--text-muted)" }}>
                      web <span style={{ color: "var(--text)" }}>v{import.meta.env.VITE_APP_VERSION ?? "0.0.0"}</span>
                    </span>
                    <span style={{ fontSize: 11, color: "var(--text-muted)" }}>
                      pi <span style={{ color: "var(--text)" }}>v{import.meta.env.VITE_PI_VERSION ?? "0.0.0"}</span>
                    </span>
                  </div>
                </div>
                <div style={{ fontSize: 14, color: "var(--text-muted)", lineHeight: 1.6, minHeight: 24 }}>
                  <Typewriter phrases={assistant?.manifest.description ? [assistant.manifest.description] : TYPEWRITER_PHRASES} />
                </div>
              </div>
            </div>
          </div>
          <div style={{ flexShrink: 0, paddingBottom: 8 }}>
            <NoticeShelf notices={notices} align="right" />
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
                gap: 8,
                maxWidth: 820,
                margin: "0 auto",
                padding: "0 52px 10px 16px",
              }}
            >
              {quickPrompts.map((action) => (
                <button
                  key={action.title}
                  type="button"
                  onClick={() => handleQuickStartPrompt(action.prompt)}
                  style={{
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "flex-start",
                    justifyContent: "center",
                    minWidth: 0,
                    minHeight: 74,
                    padding: "10px 12px",
                    borderRadius: 8,
                    border: "1px solid color-mix(in srgb, var(--border) 78%, transparent)",
                    background: "color-mix(in srgb, var(--bg-panel) 70%, var(--bg))",
                    color: "var(--text-muted)",
                    cursor: "pointer",
                    textAlign: "left",
                    lineHeight: 1.35,
                    boxShadow: "0 1px 2px rgba(15,23,42,0.03)",
                    transition: "border-color 0.12s, background 0.12s, color 0.12s, transform 0.12s",
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.borderColor = "color-mix(in srgb, var(--accent) 42%, var(--border))";
                    e.currentTarget.style.background = "var(--bg-hover)";
                    e.currentTarget.style.color = "var(--text)";
                    e.currentTarget.style.transform = "translateY(-1px)";
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.borderColor = "color-mix(in srgb, var(--border) 78%, transparent)";
                    e.currentTarget.style.background = "color-mix(in srgb, var(--bg-panel) 70%, var(--bg))";
                    e.currentTarget.style.color = "var(--text-muted)";
                    e.currentTarget.style.transform = "translateY(0)";
                  }}
                  title={action.description}
                >
                  <span style={{ fontSize: 13, fontWeight: 700, color: "var(--text)", overflowWrap: "anywhere" }}>
                    {action.title}
                  </span>
                  <span style={{
                    marginTop: 4,
                    fontSize: 11,
                    color: "var(--text-muted)",
                    overflowWrap: "anywhere",
                  }}>
                    {action.description}
                  </span>
                </button>
              ))}
            </div>
            {chatInputElement}
          </div>
        </div>
      ) : (
      <>
      <div className="relative flex flex-1 overflow-hidden">
        <div
          style={{
            position: "absolute",
            top: 12,
            left: 0,
            right: 0,
            zIndex: 40,
            padding: `0 ${CHAT_COLUMN_PADDING}px`,
            pointerEvents: "none",
          }}
        >
          <div style={{ maxWidth: 820, margin: "0 auto" }}>
            <NoticeShelf notices={notices} floating align="right" />
          </div>
        </div>
        <div ref={scrollContainerRef} className="flex-1 overflow-y-auto pt-4 [scrollbar-width:none]">
          <div style={{ padding: `0 ${CHAT_COLUMN_PADDING}px` }}>
            <div style={{ maxWidth: 820, margin: "0 auto" }}>
              <ExtensionStatusBar statuses={extensionStatuses} />
              <ExtensionWidgets widgets={aboveEditorWidgets} />

            <div style={{ height: virtualizer.getTotalSize(), position: "relative", width: "100%" }}>
              {virtualizer.getVirtualItems().map((virtualRow) => {
                const row = rows[virtualRow.index];
                const message = row.message;
                const index = row.index;
                const prevAssistantEntryId = message.role === "user" && index > 0 && messages[index - 1].role === "assistant" ? entryIds[index - 1] : undefined;
                let showTimestamp = message.role === "assistant";
                if (showTimestamp) {
                  for (let next = index + 1; next < messages.length; next += 1) {
                    if (messages[next].role === "user") break;
                    if (messages[next].role === "assistant") { showTimestamp = false; break; }
                  }
                }
                return (
                  <div
                    key={`${entryIds[index] ?? index}`}
                    data-index={virtualRow.index}
                    ref={(element) => {
                      virtualizer.measureElement(element);
                      if (index === lastUserIndex) (lastUserMsgRef as { current: HTMLDivElement | null }).current = element;
                    }}
                    style={{ position: "absolute", top: 0, left: 0, width: "100%", transform: `translateY(${virtualRow.start}px)` }}
                  >
                    <Suspense fallback={<div className="message-render-placeholder" />}>
                      <MessageView
                        message={message}
                        toolResults={toolResultsMap}
                        modelNames={modelNames}
                        entryId={entryIds[index]}
                        onFork={agentRunning || isNew || (index === 0 && message.role === "user") ? undefined : handleFork}
                        forking={forkingEntryId === entryIds[index]}
                        onNavigate={agentRunning ? undefined : handleNavigate}
                        prevAssistantEntryId={agentRunning ? undefined : prevAssistantEntryId}
                        onEditContent={(content) => chatInputRef?.current?.insertIfEmpty(content)}
                        showTimestamp={showTimestamp && !(streamState.isStreaming && index === messages.length - 1)}
                        prevTimestamp={index > 0 ? messages[index - 1].timestamp : undefined}
                        assistantId={assistantId}
                        sessionId={session?.id}
                        webExtensions={webExtensions}
                      />
                    </Suspense>
                  </div>
                );
              })}
            </div>

            {streamState.isStreaming && streamState.streamingMessage && (
              <Suspense fallback={<div className="message-render-placeholder" />}>
                <MessageView message={streamState.streamingMessage as AgentMessage} isStreaming modelNames={modelNames} assistantId={assistantId} sessionId={session?.id} webExtensions={webExtensions} />
              </Suspense>
            )}

            {agentRunning && !streamState.streamingMessage && (
              <div className="py-2 text-[13px] text-text-muted">
                <span className="animate-[pulse_1.5s_infinite]">{phaseLabel(agentPhase)}</span>
              </div>
            )}

            {agentRunning && (
              <div style={{ height: scrollContainerRef.current ? scrollContainerRef.current.clientHeight : "80vh" }} />
            )}

            <div ref={messagesEndRef} />
            </div>
          </div>
        </div>
      </div>

      <div className="relative">
        <div
          style={{
            padding: `0 ${CHAT_COLUMN_PADDING}px`,
          }}
        >
          <div style={{ maxWidth: 820, margin: "0 auto" }}>
            <ExtensionWidgets widgets={belowEditorWidgets} />
            {(latestAssistantText || speaking || ttsError) && (
              <div className="chat-tts-bar">
                <button type="button" onClick={() => speaking ? stopSpeaking() : void speak(latestAssistantText)} disabled={!latestAssistantText && !speaking}>
                  {speaking ? "■ 停止朗读" : "🔊 朗读最后回复"}
                </button>
                {ttsError && <span>{ttsError}</span>}
              </div>
            )}
          </div>
        </div>
        {chatInputElement}
      </div>
      </>
      )}
    </div>
  );
}

function ExtensionStatusBar({ statuses }: { statuses: Array<{ key: string; text: string }> }) {
  if (statuses.length === 0) return null;
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBottom: 10 }}>
      {statuses.map((status) => (
        <div
          key={status.key}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 6,
            maxWidth: "100%",
            padding: "4px 8px",
            border: "1px solid color-mix(in srgb, var(--accent) 24%, var(--border))",
            borderRadius: 6,
            background: "color-mix(in srgb, var(--accent) 7%, var(--bg))",
            color: "var(--text-muted)",
            fontSize: 12,
          }}
        >
          <span style={{ color: "var(--accent)", fontFamily: "var(--font-mono)", fontSize: 11 }}>{status.key}</span>
          <span style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{status.text}</span>
        </div>
      ))}
    </div>
  );
}

function ExtensionWidgets({ widgets }: { widgets: Array<{ key: string; lines: string[] }> }) {
  if (widgets.length === 0) return null;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 10 }}>
      {widgets.map((widget) => (
        <div
          key={widget.key}
          style={{
            border: "1px solid var(--border)",
            borderRadius: 7,
            background: "var(--bg-panel)",
            overflow: "hidden",
          }}
        >
          <div style={{ padding: "5px 9px", borderBottom: "1px solid var(--border)", color: "var(--text-dim)", fontSize: 11, fontFamily: "var(--font-mono)" }}>
            {widget.key}
          </div>
          <pre style={{ margin: 0, padding: "8px 9px", color: "var(--text-muted)", fontSize: 12, lineHeight: 1.5, whiteSpace: "pre-wrap", wordBreak: "break-word", fontFamily: "var(--font-mono)" }}>
            {widget.lines.join("\n")}
          </pre>
        </div>
      ))}
    </div>
  );
}

function NoticeShelf({ notices, floating = false, align = "left" }: { notices: NoticeItem[]; floating?: boolean; align?: "left" | "right" }) {
  if (notices.length === 0) return null;
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: align === "right" ? "flex-end" : "stretch",
        marginBottom: floating ? 0 : 10,
      }}
    >
      {notices.map((notice, index) => {
        const color = notice.type === "error"
          ? "#ef4444"
          : notice.type === "warning"
            ? "#d97706"
            : notice.type === "success"
              ? "#10b981"
              : "var(--accent)";
        return (
          <div
            key={notice.id}
            className="notice-shelf-item"
            style={{
              display: "flex",
              alignItems: "center",
              gap: 10,
              minHeight: 60,
              height: 60,
              maxHeight: 60,
              marginBottom: index === notices.length - 1 ? 0 : 6,
              overflow: "hidden",
              borderRadius: 14,
              border: "1px solid color-mix(in srgb, var(--border) 70%, transparent)",
              background: "var(--bg)",
              color: "var(--text-muted)",
              width: "fit-content",
              maxWidth: "min(100%, 620px)",
              boxShadow: floating
                ? "0 1px 2px rgba(15,23,42,0.05), 0 10px 28px -14px rgba(15,23,42,0.24)"
                : "0 1px 2px rgba(15,23,42,0.04), 0 8px 24px -12px rgba(15,23,42,0.10)",
              fontSize: 18,
              lineHeight: 1.45,
              transformOrigin: "top center",
              animation: notice.exiting
                ? "notice-shelf-out 0.18s ease-in forwards"
                : "notice-shelf-in 0.18s ease-out both",
              padding: "0 12px",
            }}
          >
            <span
              style={{
                width: 7,
                height: 7,
                borderRadius: "50%",
                background: color,
                flexShrink: 0,
              }}
            />
            <span style={{ padding: "14px 0", minWidth: 0, maxWidth: "100%", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {notice.message}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function PermissionDialog({ request, onRespond }: { request: PermissionRequest; onRespond: (decision: PermissionDecision) => void }) {
  return (
    <div className="wuxianpi-modal-backdrop permission-backdrop">
      <section className="permission-dialog" role="alertdialog" aria-modal="true" aria-label={request.title}>
        <span className="permission-icon">!</span>
        <div><span className="eyebrow">CAPABILITY REQUEST</span><h2>{request.title}</h2><p>{request.description}</p></div>
        <div className="risk-chips">{request.risk.map((risk) => <span key={risk}>{risk}</span>)}</div>
        <dl><div><dt>助手</dt><dd>{request.assistantId}</dd></div><div><dt>能力</dt><dd>{request.capabilityId}</dd></div></dl>
        <footer><button className="danger-button" onClick={() => onRespond("deny")}>拒绝</button><button className="secondary-button" onClick={() => onRespond("once")}>仅本次允许</button><button className="primary-button" onClick={() => onRespond("assistant")}>始终允许此助手</button></footer>
      </section>
    </div>
  );
}

type ExtensionDialogRequest = Extract<ExtensionUiRequest, { method: "select" | "confirm" | "input" | "editor" }>;

function ExtensionDialog({
  request,
  onRespond,
}: {
  request: ExtensionDialogRequest;
  onRespond: (request: ExtensionDialogRequest, response: { value: string } | { confirmed: boolean } | { cancelled: true }) => void;
}) {
  const [value, setValue] = useState(request.method === "editor" ? request.prefill ?? "" : "");

  useEffect(() => {
    setValue(request.method === "editor" ? request.prefill ?? "" : "");
  }, [request]);

  useEffect(() => {
    const deadline = request.expiresAt ?? (request.timeout ? Date.now() + request.timeout : null);
    if (deadline === null) return;
    const timer = window.setTimeout(() => onRespond(request, { cancelled: true }), Math.max(0, deadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [onRespond, request]);

  const submitValue = () => {
    if (request.method === "confirm") {
      onRespond(request, { confirmed: true });
    } else {
      onRespond(request, { value });
    }
  };

  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        zIndex: 90,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 20,
        background: "rgba(0,0,0,0.18)",
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        style={{
          width: "min(560px, 100%)",
          border: "1px solid var(--border)",
          borderRadius: 8,
          background: "var(--bg)",
          boxShadow: "0 20px 60px rgba(0,0,0,0.28)",
          overflow: "hidden",
        }}
      >
        <div style={{ padding: "12px 14px", borderBottom: "1px solid var(--border)" }}>
          <div style={{ color: "var(--text)", fontSize: 14, fontWeight: 650 }}>{request.title}</div>
          <div style={{ marginTop: 3, color: "var(--text-dim)", fontSize: 11, fontFamily: "var(--font-mono)" }}>扩展请求</div>
        </div>

        <div style={{ padding: 14 }}>
          {request.method === "confirm" && (
            <div style={{ color: "var(--text-muted)", fontSize: 13, lineHeight: 1.6, whiteSpace: "pre-wrap" }}>{request.message}</div>
          )}
          {request.method === "select" && (
            <div style={{ display: "grid", gap: 8 }}>
              {request.options.map((option) => (
                <button
                  key={option}
                  onClick={() => onRespond(request, { value: option })}
                  style={{
                    width: "100%",
                    padding: "9px 10px",
                    borderRadius: 7,
                    border: "1px solid var(--border)",
                    background: "var(--bg-panel)",
                    color: "var(--text)",
                    cursor: "pointer",
                    textAlign: "left",
                    fontSize: 13,
                  }}
                >
                  {option}
                </button>
              ))}
            </div>
          )}
          {request.method === "input" && (
            <input
              autoFocus
              value={value}
              placeholder={request.placeholder}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") submitValue();
                if (e.key === "Escape") onRespond(request, { cancelled: true });
              }}
              style={{
                width: "100%",
                padding: "9px 10px",
                borderRadius: 7,
                border: "1px solid var(--border)",
                background: "var(--bg-panel)",
                color: "var(--text)",
                outline: "none",
                fontSize: 13,
              }}
            />
          )}
          {request.method === "editor" && (
            <textarea
              autoFocus
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") onRespond(request, { cancelled: true });
                if ((e.metaKey || e.ctrlKey) && e.key === "Enter") submitValue();
              }}
              style={{
                width: "100%",
                minHeight: 220,
                padding: 10,
                borderRadius: 7,
                border: "1px solid var(--border)",
                background: "var(--bg-panel)",
                color: "var(--text)",
                outline: "none",
                resize: "vertical",
                fontSize: 13,
                lineHeight: 1.55,
                fontFamily: "var(--font-mono)",
              }}
            />
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, padding: "10px 14px", borderTop: "1px solid var(--border)", background: "var(--bg-panel)" }}>
          <button
            onClick={() => onRespond(request, { cancelled: true })}
            style={{
              padding: "6px 10px",
              borderRadius: 6,
              border: "1px solid var(--border)",
              background: "var(--bg)",
              color: "var(--text-muted)",
              cursor: "pointer",
            }}
          >
            取消
          </button>
          {request.method === "confirm" ? (
            <button
              onClick={submitValue}
              style={{
                padding: "6px 10px",
                borderRadius: 6,
                border: "1px solid var(--accent)",
                background: "var(--accent)",
                color: "#fff",
                cursor: "pointer",
              }}
            >
              确认
            </button>
          ) : request.method !== "select" ? (
            <button
              onClick={submitValue}
              style={{
                padding: "6px 10px",
                borderRadius: 6,
                border: "1px solid var(--accent)",
                background: "var(--accent)",
                color: "#fff",
                cursor: "pointer",
              }}
            >
              提交
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
