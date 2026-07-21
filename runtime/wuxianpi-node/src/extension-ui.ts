import { randomUUID } from "node:crypto";
import type { ExtensionUIContext } from "@earendil-works/pi-coding-agent";
import { RequestError } from "./protocol.js";

type UiResponse = { requestId: string; value?: string; confirmed?: boolean; cancelled?: boolean };
type DialogOptions = { timeout?: number; signal?: AbortSignal };
type PendingRequest = { resolve: (value: unknown) => void; fallback: unknown; timer?: NodeJS.Timeout; cleanup?: () => void };

export interface ExtensionUiState {
  statuses: Array<{ key: string; text: string }>;
  widgets: Array<{ key: string; lines: string[]; placement: "aboveEditor" | "belowEditor" }>;
}

export class ExtensionUiBridge {
  private readonly pending = new Map<string, PendingRequest>();
  private readonly statuses = new Map<string, { key: string; text: string }>();
  private readonly widgets = new Map<string, { key: string; lines: string[]; placement: "aboveEditor" | "belowEditor" }>();
  constructor(private readonly emit: (payload: unknown) => void) {}

  state(): ExtensionUiState {
    return {
      statuses: [...this.statuses.values()],
      widgets: [...this.widgets.values()],
    };
  }

  readonly context = {
    select: (title: string, options: string[], opts?: DialogOptions) =>
      this.request("select", { title, options }, opts, undefined),
    confirm: (title: string, message: string, opts?: DialogOptions) =>
      this.request("confirm", { title, message }, opts, false),
    input: (title: string, placeholder?: string, opts?: DialogOptions) =>
      this.request("input", { title, placeholder }, opts, undefined),
    notify: (message: string, notifyType?: "info" | "warning" | "error") =>
      this.fire("notify", { message, notifyType }),
    onTerminalInput: () => () => {},
    setStatus: (statusKey: string, statusText?: string) =>
      this.setStatus(statusKey, statusText),
    setWorkingMessage: (message?: string) =>
      this.fire("setWorkingMessage", { message }),
    setWorkingVisible: (visible: boolean) =>
      this.fire("setWorkingVisible", { visible }),
    setWorkingIndicator: (options?: unknown) =>
      this.fire("setWorkingIndicator", { options }),
    setHiddenThinkingLabel: (label?: string) =>
      this.fire("setHiddenThinkingLabel", { label }),
    setWidget: (widgetKey: string, content: unknown, options?: unknown) => {
      if (content === undefined || Array.isArray(content)) {
        this.setWidget(widgetKey, content, options);
      }
    },
    setFooter: () => {}, setHeader: () => {},
    setTitle: (title: string) =>
      this.fire("setTitle", { title }),
    custom: async () => undefined as never,
    pasteToEditor: (text: string) =>
      this.fire("set_editor_text", { text }),
    setEditorText: (text: string) =>
      this.fire("set_editor_text", { text }),
    getEditorText: () => "",
    editor: (title: string, prefill?: string) => this.request("editor", { title, prefill }, undefined, undefined),
    addAutocompleteProvider: () => {}, setEditorComponent: () => {}, getEditorComponent: () => undefined,
    theme: {} as never, getAllThemes: () => [], getTheme: () => undefined,
    setTheme: () => ({ success: false, error: "Theme switching is not supported by the WuxianPi host" }),
    getToolsExpanded: () => false, setToolsExpanded: () => {},
  } as unknown as ExtensionUIContext;

  respond(response: UiResponse): void {
    const pending = this.pending.get(response.requestId);
    if (!pending) throw new RequestError("unknown_ui_request", `Unknown extension UI request: ${response.requestId}`);
    this.pending.delete(response.requestId);
    if (pending.timer) clearTimeout(pending.timer);
    pending.cleanup?.();
    pending.resolve(response.cancelled ? pending.fallback : typeof response.confirmed === "boolean" ? response.confirmed : response.value);
  }

  dispose(): void {
    for (const pending of this.pending.values()) {
      if (pending.timer) clearTimeout(pending.timer);
      pending.cleanup?.();
      pending.resolve(pending.fallback);
    }
    this.pending.clear();
    this.statuses.clear();
    this.widgets.clear();
  }

  private setStatus(statusKey: string, statusText?: string): void {
    if (statusText) this.statuses.set(statusKey, { key: statusKey, text: statusText });
    else this.statuses.delete(statusKey);
    this.fire("setStatus", { statusKey, statusText });
  }

  private setWidget(widgetKey: string, content: unknown, options?: unknown): void {
    const placement = (options as { placement?: unknown } | undefined)?.placement === "belowEditor"
      ? "belowEditor" : "aboveEditor";
    if (content === undefined) {
      this.widgets.delete(widgetKey);
    } else if (Array.isArray(content)) {
      this.widgets.set(widgetKey, {
        key: widgetKey,
        lines: content.filter((line): line is string => typeof line === "string"),
        placement,
      });
    }
    this.fire("setWidget", { widgetKey, widgetLines: content, widgetPlacement: placement });
  }

  private fire(method: string, fields: Record<string, unknown>): void {
    const requestId = randomUUID();
    this.emit({ type: "extension_ui_request", id: requestId, requestId, method, ...fields });
  }

  private request(method: string, fields: Record<string, unknown>, options: DialogOptions | undefined, fallback: unknown) {
    const requestId = randomUUID();
    return new Promise<unknown>((resolve) => {
      const pending: PendingRequest = { resolve, fallback };
      const finish = () => {
        if (!this.pending.delete(requestId)) return;
        if (pending.timer) clearTimeout(pending.timer);
        pending.cleanup?.();
        resolve(fallback);
      };
      if (options?.timeout && options.timeout > 0) {
        pending.timer = setTimeout(finish, options.timeout);
        pending.timer.unref();
      }
      if (options?.signal) {
        if (options.signal.aborted) { resolve(fallback); return; }
        options.signal.addEventListener("abort", finish, { once: true });
        pending.cleanup = () => options.signal?.removeEventListener("abort", finish);
      }
      this.pending.set(requestId, pending);
      this.emit({ type: "extension_ui_request", id: requestId, requestId, method, ...fields, timeout: options?.timeout });
    });
  }
}
