"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type {
  ExtensionBridgeRequest,
  ExtensionBridgeResponse,
  JsonValue,
  WebExtensionSummary,
} from "@/lib/wuxianpi/contracts";
import { bridgeExtension, issueExtensionNonce } from "./api";

interface Props {
  extension: WebExtensionSummary;
  entry: string;
  assistantId?: string;
  sessionId?: string;
  title?: string;
  initialHeight?: number;
  className?: string;
  onClose?: () => void;
  onNotify?: (message: string) => void;
  fallback?: React.ReactNode;
  initialData?: JsonValue;
}

function resourceUrl(resourceBaseUrl: string, extensionId: string, entry: string, nonce: string): string | null {
  if (/^https?:\/\//i.test(entry) || !entry.trim()) return null;
  const safeEntry = entry.replace(/^\/+/, "").split("/").filter((part) => part && part !== "." && part !== "..").join("/");
  const params = new URLSearchParams({ extensionId, nonce });
  return `${resourceBaseUrl.replace(/\/$/, "")}/${safeEntry}?${params}`;
}

function isBridgeRequest(value: unknown): value is ExtensionBridgeRequest {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<ExtensionBridgeRequest>;
  return candidate.type === "wuxianpi_bridge_request"
    && typeof candidate.requestId === "string"
    && typeof candidate.extensionId === "string"
    && typeof candidate.nonce === "string"
    && typeof candidate.method === "string";
}

export function ExtensionHost({ extension, entry, assistantId, sessionId, title, initialHeight = 420, className, onClose, onNotify, fallback, initialData }: Props) {
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const [nonce, setNonce] = useState<string | null>(null);
  const [height, setHeight] = useState(initialHeight);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [failureReason, setFailureReason] = useState("扩展界面无法加载，已切换到通用显示。");
  const [retryKey, setRetryKey] = useState(0);
  const src = nonce ? resourceUrl(extension.resourceBaseUrl, extension.id, entry, nonce) : null;

  useEffect(() => {
    let cancelled = false;
    setNonce(null);
    setReady(false);
    setFailed(false);
    if (/^https?:\/\//i.test(entry) || !entry.trim()) {
      setFailureReason("扩展入口必须是扩展包内的相对 HTML 路径。");
      setFailed(true);
      return;
    }
    if (!assistantId) {
      setFailureReason("该扩展界面需要一个已授权的助手上下文。");
      setFailed(true);
      return;
    }
    issueExtensionNonce(extension.id, assistantId)
      .then(async (value) => {
        const candidate = resourceUrl(extension.resourceBaseUrl, extension.id, entry, value);
        if (!candidate) throw new Error("扩展入口无效");
        const response = await fetch(candidate, { cache: "no-store" });
        const contentType = response.headers.get("content-type") ?? "";
        if (!response.ok || !contentType.includes("text/html")) throw new Error(`扩展入口返回 ${response.status || "非 HTML"}`);
        if (!cancelled) setNonce(value);
      })
      .catch((reason) => {
        if (!cancelled) {
          setFailureReason(reason instanceof Error ? reason.message : String(reason));
          setFailed(true);
        }
      });
    return () => { cancelled = true; };
  }, [assistantId, entry, extension.id, extension.resourceBaseUrl, retryKey]);

  const respond = useCallback((response: ExtensionBridgeResponse) => {
    iframeRef.current?.contentWindow?.postMessage(response, "*");
  }, []);

  useEffect(() => {
    if (!nonce) return;
    const timeout = window.setTimeout(() => {
      if (!ready) setFailed(true);
    }, 8000);
    return () => window.clearTimeout(timeout);
  }, [nonce, ready]);

  useEffect(() => {
    const listener = (event: MessageEvent) => {
      if (event.source !== iframeRef.current?.contentWindow || !isBridgeRequest(event.data)) return;
      const request = event.data;
      if (!nonce || request.extensionId !== extension.id || request.nonce !== nonce) return;

      const fail = (reason: unknown): ExtensionBridgeResponse => ({
        type: "wuxianpi_bridge_response",
        requestId: request.requestId,
        extensionId: extension.id,
        nonce,
        ok: false,
        error: { code: "BRIDGE_FAILED", message: reason instanceof Error ? reason.message : String(reason) },
      });

      void bridgeExtension(extension.id, request).then((response) => {
        if (response.ok) {
          const params = request.params && typeof request.params === "object" && !Array.isArray(request.params) ? request.params : {};
          if (request.method === "ui.resize") {
            const requested = Number((params as Record<string, JsonValue>).height);
            if (Number.isFinite(requested)) setHeight(Math.min(1200, Math.max(120, requested)));
          } else if (request.method === "ui.close") {
            onClose?.();
          } else if (request.method === "ui.notify") {
            const message = String((params as Record<string, JsonValue>).message ?? "");
            if (message) onNotify?.(message);
          }
        }
        respond(response);
      }).catch((error) => respond(fail(error)));
    };
    window.addEventListener("message", listener);
    return () => window.removeEventListener("message", listener);
  }, [assistantId, extension.id, nonce, onClose, onNotify, respond, sessionId]);

  if (failed) {
    return (
      <div className={["wuxianpi-extension-fallback", className].filter(Boolean).join(" ")}>
        <strong>{title ?? extension.manifest.name}</strong>
        <span>{failureReason}</span>
        {fallback}
        <button type="button" onClick={() => { setFailed(false); setReady(false); setRetryKey((value) => value + 1); }}>重试</button>
      </div>
    );
  }

  return (
    <section className={["wuxianpi-extension-host", className].filter(Boolean).join(" ")}>
      {title && <header><span>{title}</span>{onClose && <button type="button" onClick={onClose} aria-label="关闭扩展">×</button>}</header>}
      {!ready && <div className="wuxianpi-extension-loading">正在加载 {extension.manifest.name}…</div>}
      {src && <iframe
        ref={iframeRef}
        src={src}
        title={title ?? extension.manifest.name}
        sandbox="allow-scripts allow-forms allow-downloads"
        referrerPolicy="no-referrer"
        loading="lazy"
        style={{ height }}
        onLoad={() => {
          setReady(true);
          setFailed(false);
          iframeRef.current?.contentWindow?.postMessage({
            type: "wuxianpi_host_context",
            extensionId: extension.id,
            nonce,
            assistantId: assistantId ?? null,
            sessionId: sessionId ?? null,
            data: initialData ?? null,
          }, "*");
        }}
        onError={() => setFailed(true)}
      />}
    </section>
  );
}

export function matchToolPattern(pattern: string, toolName: string): boolean {
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*");
  return new RegExp(`^${escaped}$`).test(toolName);
}
