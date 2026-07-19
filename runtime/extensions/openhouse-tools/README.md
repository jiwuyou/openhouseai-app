# OpenHouse Pi tools extension

Load this directory with Pi's `--extension` option or install it under Pi's
extension discovery directory. Its manifest requests only `exec`:

- Every `code_runner`, `ubuntu_exec`, and Android Bridge call gets a new
  subprocess and isolated stdout/stderr/cancellation state.
- The Android Bridge helper reads a gateway-owned, uncredentialed
  `OPENHOUSE_ANDROID_BRIDGE_URL`. The real Android Bridge token remains only in
  the gateway process.

The deployment must explicitly allow the `exec` capability in Pi's extension
policy. No runtime credential is embedded in this extension. Android calls use
a fresh `sh`/`curl` subprocess pointed at the gateway proxy. The gateway adds
the real Bridge credential upstream, so it never enters Pi, QuickJS, tool
arguments, or ordinary shell environments.

The Android Bridge contract is:

```text
POST $OPENHOUSE_ANDROID_BRIDGE_URL/v1/tools/{toolName}
Content-Type: application/json

{"id":"tool-call-id","arguments":{}}
```

The Bridge should reply with an HTTP 2xx JSON body such as:

```json
{"callId":"tool-call-id","isError":false,"content":{}}
```

or an error body:

```json
{"callId":"tool-call-id","isError":true,"content":{},"error":{"code":"permission_denied","message":"permission denied","retryable":false}}
```

Every tool catches spawn, timeout, missing-runtime, HTTP, and malformed-response
failures and returns a Pi `ToolResult` with `isError: true`. The extension never
throws an operational tool failure out of `execute`, never retries, and never
continues the Agent on its own.
