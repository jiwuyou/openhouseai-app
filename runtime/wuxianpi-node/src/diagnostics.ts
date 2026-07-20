import { appendFile, mkdir, readFile, rename, stat, unlink, writeFile } from "node:fs/promises";
import { join } from "node:path";

const DEFAULT_FILE_BYTES = 1024 * 1024;
const DEFAULT_FILE_COUNT = 5;
const DEFAULT_QUEUE_RECORDS = 512;
const DEFAULT_STREAM_SUMMARY_INTERVAL_MS = 10_000;
const MAX_DETAIL_DURATION_MS = 120_000;
const MAX_RECORD_BYTES = 64 * 1024;

export interface DiagnosticsOptions {
  directory: string;
  maxFileBytes?: number;
  maxFiles?: number;
  maxQueuedRecords?: number;
  streamSummaryIntervalMs?: number;
  now?: () => Date;
}

export interface DiagnosticsStatus {
  enabled: true;
  directory: string;
  path: string;
  detailed: boolean;
  detailEnabled: boolean;
  detailedUntil?: string;
  detailUntil?: number;
  maxDetailDurationMs: number;
  maxFileBytes: number;
  maxFiles: number;
  capacityBytes: number;
  maxQueuedRecords: number;
  queuedRecords: number;
  aggregatedStreams: number;
  droppedRecords: number;
  writeErrors: number;
  recordsQueued: number;
}

export interface StreamEventDimensions {
  stage: string;
  sessionId?: string;
  eventStreamId?: string;
  connectionId?: string;
  eventType: string;
  sequence?: number;
  bytes?: number;
  targetCount?: number;
}

type LogFields = Record<string, unknown>;

interface QueuedRecord {
  encoded: string;
  bytes: number;
}

interface StreamSummary {
  dimensions: Omit<StreamEventDimensions, "sequence" | "bytes" | "targetCount">;
  count: number;
  totalBytes: number;
  totalTargets: number;
  firstSequence?: number;
  lastSequence?: number;
  firstAt: string;
  lastAt: string;
}

/**
 * Best-effort bounded JSONL diagnostics. The record queue has a hard capacity;
 * saturation increments droppedRecords instead of retaining Promise closures
 * or applying backpressure to agent/session work.
 */
export class PersistentDiagnostics {
  readonly directory: string;
  private readonly maxFileBytes: number;
  private readonly maxFiles: number;
  private readonly maxQueuedRecords: number;
  private readonly now: () => Date;
  private readonly queue: QueuedRecord[] = [];
  private readonly streamSummaries = new Map<string, StreamSummary>();
  private readonly summaryTimer: NodeJS.Timeout;
  private processing = false;
  private idlePromise: Promise<void> = Promise.resolve();
  private resolveIdle?: () => void;
  private currentBytes: number | undefined;
  private detailedUntilMs = 0;
  private droppedRecords = 0;
  private writeErrors = 0;
  private acceptedRecords = 0;
  private recordSequence = 0;
  private closed = false;

  constructor(options: DiagnosticsOptions) {
    this.directory = options.directory;
    this.maxFileBytes = Math.max(4096, options.maxFileBytes ?? DEFAULT_FILE_BYTES);
    this.maxFiles = Math.max(1, options.maxFiles ?? DEFAULT_FILE_COUNT);
    this.maxQueuedRecords = Math.max(8, options.maxQueuedRecords ?? DEFAULT_QUEUE_RECORDS);
    this.now = options.now ?? (() => new Date());
    const intervalMs = Math.max(1_000, options.streamSummaryIntervalMs ?? DEFAULT_STREAM_SUMMARY_INTERVAL_MS);
    this.summaryTimer = setInterval(() => this.flushStreamSummaries("periodic"), intervalMs);
    this.summaryTimer.unref();
  }

  status(): DiagnosticsStatus {
    const detailed = this.isDetailed();
    return {
      enabled: true,
      directory: this.directory,
      path: this.directory,
      detailed,
      detailEnabled: detailed,
      detailedUntil: detailed ? new Date(this.detailedUntilMs).toISOString() : undefined,
      detailUntil: detailed ? this.detailedUntilMs : undefined,
      maxDetailDurationMs: MAX_DETAIL_DURATION_MS,
      maxFileBytes: this.maxFileBytes,
      maxFiles: this.maxFiles,
      capacityBytes: this.maxFileBytes * this.maxFiles,
      maxQueuedRecords: this.maxQueuedRecords,
      queuedRecords: this.queue.length,
      aggregatedStreams: this.streamSummaries.size,
      droppedRecords: this.droppedRecords,
      writeErrors: this.writeErrors,
      // Kept as a compatibility alias for clients already displaying this field.
      recordsQueued: this.acceptedRecords,
    };
  }

  setDetailed(enabled: boolean, durationMs = MAX_DETAIL_DURATION_MS): DiagnosticsStatus {
    this.flushStreamSummaries(enabled ? "detail_enabled" : "detail_disabled");
    const boundedDuration = Math.max(1_000, Math.min(MAX_DETAIL_DURATION_MS, Math.floor(durationMs)));
    this.detailedUntilMs = enabled ? this.now().getTime() + boundedDuration : 0;
    this.record("diagnostics.detail", { enabled, durationMs: enabled ? boundedDuration : 0 });
    return this.status();
  }

  record(event: string, fields: LogFields = {}, detailedFields?: LogFields): boolean {
    if (this.closed) return false;
    const timestamp = this.now().toISOString();
    const entry: LogFields = {
      timestamp,
      recordSequence: ++this.recordSequence,
      event,
      droppedRecords: this.droppedRecords,
      ...(sanitize(fields) as LogFields),
    };
    if (detailedFields && this.isDetailed()) entry.detail = sanitize(detailedFields);
    let encoded = `${JSON.stringify(entry)}\n`;
    if (Buffer.byteLength(encoded) > MAX_RECORD_BYTES) {
      encoded = `${JSON.stringify({
        timestamp,
        recordSequence: this.recordSequence,
        event,
        droppedRecords: this.droppedRecords,
        truncated: true,
        originalBytes: Buffer.byteLength(encoded),
        ...(sanitize(fields) as LogFields),
      })}\n`;
    }
    const queued = { encoded, bytes: Buffer.byteLength(encoded) };
    if (this.queue.length >= this.maxQueuedRecords) {
      this.droppedRecords++;
      return false;
    }
    this.queue.push(queued);
    this.acceptedRecords++;
    this.startPump();
    return true;
  }

  /**
   * High-frequency SDK events are counted in normal mode. Detailed mode keeps
   * one sanitized structural record per event for short reproduction windows.
   */
  recordStream(dimensions: StreamEventDimensions, detailedFields?: LogFields): void {
    if (this.isDetailed()) {
      this.record("stream.event", { ...dimensions }, detailedFields);
      return;
    }
    const key = [
      dimensions.stage,
      dimensions.connectionId ?? "",
      dimensions.sessionId ?? "",
      dimensions.eventStreamId ?? "",
      dimensions.eventType,
    ].join("\u0000");
    const timestamp = this.now().toISOString();
    const existing = this.streamSummaries.get(key);
    if (existing) {
      existing.count++;
      existing.totalBytes += dimensions.bytes ?? 0;
      existing.totalTargets += dimensions.targetCount ?? 0;
      existing.lastAt = timestamp;
      if (dimensions.sequence !== undefined) existing.lastSequence = dimensions.sequence;
      return;
    }
    if (this.streamSummaries.size >= this.maxQueuedRecords) {
      this.droppedRecords++;
      return;
    }
    this.streamSummaries.set(key, {
      dimensions: {
        stage: dimensions.stage,
        connectionId: dimensions.connectionId,
        sessionId: dimensions.sessionId,
        eventStreamId: dimensions.eventStreamId,
        eventType: dimensions.eventType,
      },
      count: 1,
      totalBytes: dimensions.bytes ?? 0,
      totalTargets: dimensions.targetCount ?? 0,
      firstSequence: dimensions.sequence,
      lastSequence: dimensions.sequence,
      firstAt: timestamp,
      lastAt: timestamp,
    });
  }

  flushStreamSummaries(reason: string, filter: { sessionId?: string; connectionId?: string } = {}): void {
    for (const [key, summary] of this.streamSummaries) {
      if (filter.sessionId !== undefined && summary.dimensions.sessionId !== filter.sessionId) continue;
      if (filter.connectionId !== undefined && summary.dimensions.connectionId !== filter.connectionId) continue;
      const accepted = this.record("stream.summary", {
        ...summary.dimensions,
        count: summary.count,
        totalBytes: summary.totalBytes,
        totalTargets: summary.totalTargets,
        firstSequence: summary.firstSequence,
        lastSequence: summary.lastSequence,
        firstAt: summary.firstAt,
        lastAt: summary.lastAt,
        reason,
      });
      if (accepted) this.streamSummaries.delete(key);
    }
  }

  async exportSnapshot(): Promise<{ path: string; content: string; size: number; sizeBytes: number; files: string[] }> {
    this.flushStreamSummaries("export");
    await this.flush();
    if (this.streamSummaries.size > 0) {
      this.flushStreamSummaries("export_retry");
      await this.flush();
    }
    await mkdir(this.directory, { recursive: true });
    const parts: string[] = [];
    const files: string[] = [];
    for (let index = this.maxFiles - 1; index >= 0; index--) {
      const path = this.logPath(index);
      try {
        const content = await readFile(path, "utf8");
        if (content.length > 0) {
          parts.push(content.endsWith("\n") ? content : `${content}\n`);
          files.push(path);
        }
      } catch (error) {
        if (!isNotFound(error)) throw error;
      }
    }
    const content = parts.join("");
    const path = join(this.directory, "wuxianpi-diagnostics-export.jsonl");
    await writeFile(path, content, "utf8");
    const size = Buffer.byteLength(content);
    return { path, content, size, sizeBytes: size, files };
  }

  async flush(): Promise<void> {
    while (this.processing) await this.idlePromise;
  }

  async close(): Promise<void> {
    if (this.closed) return;
    clearInterval(this.summaryTimer);
    this.flushStreamSummaries("shutdown");
    this.closed = true;
    await this.flush();
  }

  private isDetailed(): boolean {
    if (this.detailedUntilMs <= this.now().getTime()) {
      this.detailedUntilMs = 0;
      return false;
    }
    return true;
  }

  private startPump(): void {
    if (this.processing) return;
    this.processing = true;
    this.idlePromise = new Promise((resolve) => { this.resolveIdle = resolve; });
    void this.pump();
  }

  private async pump(): Promise<void> {
    while (this.queue.length > 0) {
      const queued = this.queue[0];
      if (!queued) break;
      try {
        await this.writeRecord(queued);
      } catch {
        this.writeErrors++;
      } finally {
        this.queue.shift();
      }
    }
    this.processing = false;
    this.resolveIdle?.();
    this.resolveIdle = undefined;
  }

  private async writeRecord(record: QueuedRecord): Promise<void> {
    await this.ensureReady();
    if ((this.currentBytes ?? 0) > 0 && (this.currentBytes ?? 0) + record.bytes > this.maxFileBytes) {
      await this.rotate();
    }
    await appendFile(this.logPath(0), record.encoded, "utf8");
    this.currentBytes = (this.currentBytes ?? 0) + record.bytes;
  }

  private async ensureReady(): Promise<void> {
    await mkdir(this.directory, { recursive: true });
    if (this.currentBytes !== undefined) return;
    try {
      this.currentBytes = (await stat(this.logPath(0))).size;
    } catch (error) {
      if (!isNotFound(error)) throw error;
      this.currentBytes = 0;
    }
  }

  private async rotate(): Promise<void> {
    await unlink(this.logPath(this.maxFiles - 1)).catch((error) => {
      if (!isNotFound(error)) throw error;
    });
    for (let index = this.maxFiles - 2; index >= 0; index--) {
      await rename(this.logPath(index), this.logPath(index + 1)).catch((error) => {
        if (!isNotFound(error)) throw error;
      });
    }
    this.currentBytes = 0;
  }

  private logPath(index: number): string {
    return join(this.directory, `diagnostics-${index}.jsonl`);
  }
}

function sanitize(value: unknown, key = "", depth = 0): unknown {
  if (isSensitiveKey(key)) return "[REDACTED]";
  if (depth > 6) return "[MAX_DEPTH]";
  if (value === null || value === undefined || typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "bigint") return value.toString();
  if (typeof value === "string") return redactString(value);
  if (value instanceof Date) return value.toISOString();
  if (value instanceof Error) {
    return {
      name: value.name,
      message: redactString(value.message),
      stack: value.stack ? redactString(value.stack, 2048) : undefined,
    };
  }
  if (Array.isArray(value)) return value.slice(0, 100).map((item) => sanitize(item, key, depth + 1));
  if (typeof value === "object") {
    const output: LogFields = {};
    for (const [childKey, childValue] of Object.entries(value as LogFields).slice(0, 100)) {
      output[childKey] = sanitize(childValue, childKey, depth + 1);
    }
    return output;
  }
  return String(value);
}

function redactString(value: string, maximum = 512): string {
  return value
    .replace(/\bBearer\s+[A-Za-z0-9._~+\/-]+=*/gi, "Bearer [REDACTED]")
    .replace(/\bsk-[A-Za-z0-9_-]{8,}\b/g, "sk-[REDACTED]")
    .slice(0, maximum);
}

function isSensitiveKey(key: string): boolean {
  const normalized = key.replaceAll(/[-_]/g, "").toLowerCase();
  if (normalized.includes("apikey") || normalized.includes("authorization") || normalized.includes("password") ||
      normalized.includes("secret") || normalized.includes("token") || normalized.includes("cookie") ||
      normalized.endsWith("key")) return true;
  return BODY_KEYS.has(normalized);
}

const BODY_KEYS = new Set([
  "content", "text", "delta", "partial", "partialresult", "prompt", "input", "output", "args", "arguments", "result",
  "image", "images", "toolcall", "toolresult", "message", "messages",
]);

function isNotFound(error: unknown): boolean {
  return !!error && typeof error === "object" && "code" in error && (error as { code?: string }).code === "ENOENT";
}
