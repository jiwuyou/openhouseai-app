//! Node.js `http` and `https` shim — pure-JS implementation for the QuickJS
//! extension runtime.
//!
//! Provides `http.request`, `http.get`, `https.request`, `https.get` that route
//! all HTTP traffic through the capability-gated `pi.http()` hostcall. Uses the
//! `EventEmitter` from `node:events` for the standard Node.js event-based API.

/// The JS source for the `node:http` virtual module.
pub const NODE_HTTP_JS: &str = r#"
import EventEmitter from "node:events";

// ─── STATUS_CODES ────────────────────────────────────────────────────────────

const STATUS_CODES = {
  200: 'OK', 201: 'Created', 204: 'No Content',
  301: 'Moved Permanently', 302: 'Found', 304: 'Not Modified',
  400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden',
  404: 'Not Found', 405: 'Method Not Allowed', 408: 'Request Timeout',
  500: 'Internal Server Error', 502: 'Bad Gateway', 503: 'Service Unavailable',
};

const METHODS = [
  'GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'CONNECT',
  'OPTIONS', 'TRACE', 'PATCH',
];

function __pi_http_is_binary_chunk(chunk) {
  if (typeof Buffer !== 'undefined' && Buffer.isBuffer && Buffer.isBuffer(chunk)) {
    return true;
  }
  if (chunk instanceof Uint8Array || chunk instanceof ArrayBuffer) {
    return true;
  }
  return !!(ArrayBuffer.isView && ArrayBuffer.isView(chunk));
}

function __pi_http_to_uint8(chunk) {
  if (typeof Buffer !== 'undefined' && Buffer.isBuffer && Buffer.isBuffer(chunk)) {
    return new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
  }
  if (chunk instanceof Uint8Array) {
    return chunk;
  }
  if (chunk instanceof ArrayBuffer) {
    return new Uint8Array(chunk);
  }
  if (ArrayBuffer.isView && ArrayBuffer.isView(chunk)) {
    return new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
  }
  return new TextEncoder().encode(String(chunk ?? ''));
}

function __pi_http_clone_body_chunk(chunk) {
  const view = __pi_http_to_uint8(chunk);
  if (typeof Buffer !== 'undefined' && typeof Buffer.from === 'function') {
    return Buffer.from(view);
  }
  return new Uint8Array(view);
}

function __pi_http_chunks_to_base64(chunks) {
  const parts = chunks.map((chunk) => __pi_http_to_uint8(chunk));
  const total = parts.reduce((sum, part) => sum + part.byteLength, 0);
  const merged =
    typeof Buffer !== 'undefined' && typeof Buffer.alloc === 'function'
      ? Buffer.alloc(total)
      : new Uint8Array(total);

  let offset = 0;
  for (const part of parts) {
    merged.set(part, offset);
    offset += part.byteLength;
  }

  if (typeof globalThis.__pi_base64_encode_bytes_native === 'function') {
    return __pi_base64_encode_bytes_native(merged);
  }

  // Fallback for older runtime bounds
  let binary = '';
  let chunk = [];
  for (let i = 0; i < merged.length; i++) {
    chunk.push(merged[i]);
    if (chunk.length >= 4096) {
      binary += String.fromCharCode.apply(null, chunk);
      chunk.length = 0;
    }
  }
  if (chunk.length > 0) {
    binary += String.fromCharCode.apply(null, chunk);
  }
  return __pi_base64_encode_native(binary);
}

function __pi_http_decode_body_bytes(bodyBytes) {
  const encoded = String(bodyBytes ?? '');
  const binary = globalThis.__pi_base64_decode_native(encoded);
  const out =
    typeof Buffer !== 'undefined' && typeof Buffer.alloc === 'function'
      ? Buffer.alloc(binary.length)
      : new Uint8Array(binary.length);

  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i) & 0xff;
  }
  return out;
}

function __pi_http_decode_chunk(chunk, encoding) {
  if (!encoding || typeof chunk === 'string') {
    return chunk;
  }

  const bytes = __pi_http_to_uint8(chunk);
  if (typeof Buffer !== 'undefined' && typeof Buffer.from === 'function') {
    return Buffer.from(bytes).toString(encoding);
  }
  return new TextDecoder(encoding).decode(bytes);
}

// ─── IncomingMessage ─────────────────────────────────────────────────────────

class IncomingMessage extends EventEmitter {
  constructor(statusCode, headers, body) {
    super();
    this.statusCode = statusCode;
    this.statusMessage = STATUS_CODES[statusCode] || 'Unknown';
    this.headers = headers || {};
    this._body = body || '';
    this._destroyed = false;
    this.complete = false;
    this.httpVersion = '1.1';
    this.method = null;
    this.url = '';
  }

  _deliver() {
    if (this._destroyed) {
      return;
    }

    const chunk = __pi_http_decode_chunk(this._body, this._encoding);
    if (chunk && chunk.length > 0) {
      this.emit('data', chunk);
    }

    if (this._destroyed) {
      return;
    }

    this.complete = true;
    this.emit('end');
  }

  setEncoding(encoding) {
    this._encoding = encoding ? String(encoding) : 'utf8';
    return this;
  }
  resume() { return this; }
  pause() { return this; }
  destroy() {
    if (this._destroyed) {
      return this;
    }
    this._destroyed = true;
    this.emit('close');
    return this;
  }
}

// ─── ClientRequest ───────────────────────────────────────────────────────────

class ClientRequest extends EventEmitter {
  constructor(options, callback) {
    super();
    this._options = options;
    this._body = [];
    this._ended = false;
    this._aborted = false;
    this._headers = {};
    this.socket = { remoteAddress: '127.0.0.1', remotePort: 0 };
    this.method = options.method || 'GET';
    this.path = options.path || '/';

    if (options.headers) {
      for (const [k, v] of Object.entries(options.headers)) {
        this._headers[String(k).toLowerCase()] = String(v);
      }
    }

    if (typeof callback === 'function') {
      this.once('response', callback);
    }
  }

  write(chunk) {
    if (!this._ended && !this._aborted) {
      this._body.push(
        __pi_http_is_binary_chunk(chunk)
          ? __pi_http_clone_body_chunk(chunk)
          : String(chunk)
      );
    }
    return true;
  }

  end(chunk, _encoding, callback) {
    if (typeof chunk === 'function') { callback = chunk; chunk = undefined; }
    if (typeof _encoding === 'function') { callback = _encoding; }
    if (chunk) this.write(chunk);
    if (typeof callback === 'function') this.once('finish', callback);

    this._ended = true;
    this._send();
    return this;
  }

  abort() {
    this._aborted = true;
    this.emit('abort');
    this.destroy();
  }

  destroy(error) {
    this._aborted = true;
    if (error) this.emit('error', error);
    this.emit('close');
    return this;
  }

  setTimeout(ms, callback) {
    if (typeof callback === 'function') this.once('timeout', callback);
    this._timeoutMs = ms;
    return this;
  }

  setNoDelay() { return this; }
  setSocketKeepAlive() { return this; }
  flushHeaders() {}
  getHeader(name) { return this._headers[String(name).toLowerCase()]; }
  setHeader(name, value) {
    if (!this._ended && !this._aborted) {
      this._headers[String(name).toLowerCase()] = String(value);
    }
    return this;
  }
  removeHeader(name) {
    if (!this._ended && !this._aborted) {
      delete this._headers[String(name).toLowerCase()];
    }
    return this;
  }

  _send() {
    if (this._aborted) {
      return;
    }

    const opts = this._options;
    const protocol = opts.protocol || 'http:';
    const hostname = opts.hostname || opts.host || 'localhost';
    const port = opts.port ? `:${opts.port}` : '';
    const path = opts.path || '/';
    const url = `${protocol}//${hostname}${port}${path}`;

    const headers = { ...this._headers };

    const method = (opts.method || 'GET').toUpperCase();
    const request = { url, method, headers };
    if (this._body.length > 0) {
      const hasBinaryChunk = this._body.some((chunk) => __pi_http_is_binary_chunk(chunk));
      if (hasBinaryChunk) {
        request.body_bytes = __pi_http_chunks_to_base64(this._body);
      } else {
        request.body = this._body.join('');
      }
    }
    if (this._timeoutMs) request.timeout = this._timeoutMs;

    // Use pi.http() hostcall if available
    if (typeof globalThis.pi === 'object' && typeof globalThis.pi.http === 'function') {
      try {
        const promise = globalThis.pi.http(request);
        if (promise && typeof promise.then === 'function') {
          promise.then(
            (result) => {
              if (!this._aborted) {
                this._handleResponse(result);
              }
            },
            (err) => {
              if (!this._aborted) {
                this.emit('error', typeof err === 'string' ? new Error(err) : err);
              }
            }
          );
        } else {
          this._handleResponse(promise);
        }
      } catch (err) {
        this.emit('error', err);
      }
    } else {
      // No pi.http available — emit error
      this.emit('error', new Error('HTTP requests require pi.http() hostcall'));
    }

    this.emit('finish');
  }

  _handleResponse(result) {
    if (this._aborted) {
      return;
    }

    if (!result || typeof result !== 'object') {
      this.emit('error', new Error('Invalid HTTP response from hostcall'));
      return;
    }

    const statusCode = result.status || result.statusCode || 200;
    const headers = result.headers || {};
    const body =
      result.body_bytes !== undefined && result.body_bytes !== null
        ? __pi_http_decode_body_bytes(result.body_bytes)
        : (result.body || result.data || '');

    const res = new IncomingMessage(statusCode, headers, body);
    this.emit('response', res);
    // Deliver body asynchronously (in next microtask)
    Promise.resolve().then(() => {
      if (!this._aborted) {
        res._deliver();
      }
    });
  }
}

// ─── Module API ──────────────────────────────────────────────────────────────

function _parseOptions(input, options) {
  if (typeof input === 'string') {
    try {
      const url = new URL(input);
      return {
        protocol: url.protocol,
        hostname: url.hostname,
        port: url.port || undefined,
        path: url.pathname + url.search,
        ...(options || {}),
      };
    } catch (_e) {
      return { path: input, ...(options || {}) };
    }
  }
  if (input && typeof input === 'object' && !(input instanceof URL)) {
    return { ...input };
  }
  if (input instanceof URL) {
    return {
      protocol: input.protocol,
      hostname: input.hostname,
      port: input.port || undefined,
      path: input.pathname + input.search,
      ...(options || {}),
    };
  }
  return options || {};
}

export function request(input, optionsOrCallback, callback) {
  let options;
  if (typeof optionsOrCallback === 'function') {
    callback = optionsOrCallback;
    options = _parseOptions(input);
  } else {
    options = _parseOptions(input, optionsOrCallback);
  }
  if (!options.protocol) options.protocol = 'http:';
  return new ClientRequest(options, callback);
}

export function get(input, optionsOrCallback, callback) {
  const req = request(input, optionsOrCallback, callback);
  req.end();
  return req;
}

export function createServer() {
  throw new Error('node:http.createServer is not available in PiJS');
}

export { STATUS_CODES, METHODS, IncomingMessage, ClientRequest };
export default { request, get, createServer, STATUS_CODES, METHODS, IncomingMessage, ClientRequest };
"#;

/// The JS source for the `node:https` virtual module.
pub const NODE_HTTPS_JS: &str = r#"
import EventEmitter from "node:events";
import * as http from "node:http";

export function request(input, optionsOrCallback, callback) {
  let options;
  if (typeof optionsOrCallback === 'function') {
    callback = optionsOrCallback;
    options = typeof input === 'string' || input instanceof URL
      ? { ...(typeof input === 'string' ? (() => { try { const u = new URL(input); return { protocol: u.protocol, hostname: u.hostname, port: u.port, path: u.pathname + u.search }; } catch(_) { return { path: input }; } })() : { protocol: input.protocol, hostname: input.hostname, port: input.port, path: input.pathname + input.search }) }
      : { ...(input || {}) };
  } else {
    options = typeof input === 'string' || input instanceof URL
      ? { ...(typeof input === 'string' ? (() => { try { const u = new URL(input); return { protocol: u.protocol, hostname: u.hostname, port: u.port, path: u.pathname + u.search }; } catch(_) { return { path: input }; } })() : { protocol: input.protocol, hostname: input.hostname, port: input.port, path: input.pathname + input.search }), ...(optionsOrCallback || {}) }
      : { ...(input || {}), ...(optionsOrCallback || {}) };
  }
  if (!options.protocol) options.protocol = 'https:';
  return http.request(options, callback);
}

export function get(input, optionsOrCallback, callback) {
  const req = request(input, optionsOrCallback, callback);
  req.end();
  return req;
}

export function createServer() {
  throw new Error('node:https.createServer is not available in PiJS');
}

export const globalAgent = {};

export default { request, get, createServer, globalAgent };
"#;
