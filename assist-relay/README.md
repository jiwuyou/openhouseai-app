# WuxianPi Assist Relay

Minimal public relay for WuxianPi remote assistance. It pairs one `HOST` and one
`ASSIST` connection per room and forwards application frames unchanged. The
relay does not decrypt, inspect, cache, or persist application payloads.

## Protocol

Health check:

```text
GET /health
```

WebSocket connection:

```text
/relay?v=1&room=<room-id>&role=HOST|ASSIST
```

Room IDs must contain 16 to 128 URL-safe alphanumeric, underscore, or hyphen
characters. Each room accepts at most one connection for each role. A duplicate
role is closed with code `4409` and reason `duplicate_role`.

Application frames may be text or binary and are forwarded without parsing.
Frames sent before the other role connects are dropped. Relay-owned text control
frames have `"relay": 1` and currently include:

```json
{"relay":1,"type":"peer_status","status":"waiting"}
{"relay":1,"type":"peer_status","status":"connected","role":"ASSIST"}
{"relay":1,"type":"peer_left","role":"HOST"}
```

The default maximum application frame is 1 MiB. The relay does not retain frames
for reconnecting clients. A room is removed as soon as both peers disconnect.

## Run

```bash
npm ci
npm run build
npm start
```

The server listens on `0.0.0.0:20876` by default.

| Environment variable | Default | Meaning |
| --- | ---: | --- |
| `HOST` | `0.0.0.0` | Listen address |
| `PORT` | `20876` | Listen port |
| `MAX_FRAME_BYTES` | `1048576` | Maximum incoming WebSocket message |
| `MAX_BUFFERED_BYTES` | `4194304` | Maximum queued bytes for a slow peer |
| `PING_INTERVAL_MS` | `30000` | WebSocket ping interval |
| `EMPTY_ROOM_TTL_MS` | `60000` | Defensive cleanup TTL for empty rooms |

Run tests with:

```bash
npm test
```

## Container

```bash
docker build -t wuxianpi-assist-relay .
docker run --rm -p 20876:20876 wuxianpi-assist-relay
```

TLS termination and public certificates are intentionally outside this service.
