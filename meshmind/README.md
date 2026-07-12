# MeshMind Observer Dashboard

Live command-center dashboard for the MeshMind offline emergency mesh
network (Android BLE/Wi-Fi-Direct nodes + Arduino UNO Q SOS node + gateway).
Everything rendered is driven by real telemetry received over the network —
the dashboard itself contains no hardcoded nodes, packets, or simulated
values anywhere in its code path.

## Architecture

```
shared/                  Protocol contract — imported by every other layer
  constants.py              ports, timeouts, thresholds (all env-overridable)
  schema.py                 TelemetryPacket + tolerant JSON parsing

backend/                 Communication layer + data management layer
  state_manager.py          the ONLY component that mutates mesh state
  tcp_listener.py            NDJSON-over-TCP telemetry ingest (port 5000)
  ws_broadcaster.py          live push to the dashboard (port 8765)
  http_api.py                 GET /api/state snapshot + /health (port 8000)
  server.py                    runs all of the above concurrently

frontend/                Visualization layer — Streamlit + embedded D3
  app.py                     thin Streamlit entrypoint (config + embed)
  components/mesh_dashboard.html
                             self-contained HTML/CSS/D3 dashboard: force-
                             directed topology graph, device cards,
                             emergency feed, Arduino sensor panel, event
                             timeline, packet log. Opens its own WebSocket
                             connection and animates independently of
                             Streamlit's rerun cycle.

scripts/
  dev_telemetry_sender.py   DEV/TEST UTILITY ONLY — sends real NDJSON
                             packets over TCP so you can validate the
                             pipeline before real phones/Arduino are wired
                             up. Not part of the dashboard; don't run it
                             during the actual demo.
```

Data flows one direction only: **mesh node → TCP listener → state manager →
(WebSocket broadcaster | HTTP API) → dashboard**. The visualization layer
never touches a raw packet; it only ever reads state-manager output.

## Running it

Install dependencies (Python 3.10+):

```bash
pip install -r requirements.txt
```

Start the backend (from the project root):

```bash
python -m backend.server
# TCP telemetry : tcp://0.0.0.0:5000
# WebSocket     : ws://0.0.0.0:8765
# HTTP state API: http://0.0.0.0:8000/api/state
```

Start the dashboard in another terminal:

```bash
streamlit run frontend/app.py
```

Point the sidebar's "Backend host" at wherever `backend/server.py` is
running (defaults to `localhost:8765`).

### Testing the pipeline before real devices are connected

```bash
python -m scripts.dev_telemetry_sender --host 127.0.0.1 --port 5000
```

This opens a real TCP connection and sends real NDJSON packets using the
exact same wire protocol a phone or the Arduino would use — it's a genuine
integration test client, not fake data baked into the dashboard. Stop it
(Ctrl+C) once you've confirmed the graph, cards, and feed are updating, and
only real mesh telemetry will drive the dashboard from then on.

## Telemetry protocol

One JSON object per line (NDJSON) over TCP port 5000 (configurable via
`MESHMIND_TCP_PORT`). Example:

```json
{
  "packet_id": "uuid-optional",
  "packet_type": "sos",
  "node_id": "arduino-01",
  "timestamp": 1735689600.0,
  "device_type": "arduino_uno_q",
  "neighbors": ["phone-a"],
  "status": "online",
  "battery": 71.5,
  "signal_strength": -62,
  "emergency_class": "fire",
  "confidence": 0.94,
  "route": ["arduino-01", "phone-a"],
  "payload": {"message": "SOS triggered manually"},
  "sensors": {"smoke_level": 87.2, "temperature_c": 41.0}
}
```

- `packet_type`: `heartbeat | telemetry | emergency_alert | chat_message | sos | route_update | join | leave`
- `device_type`: `android_phone | arduino_uno_q | gateway | ai_pc`
- Unknown fields are ignored. Unknown enum values fall back to
  `unknown`/`none` rather than raising. Only `node_id` is required — every
  other field is optional and defaults to `None`/empty, never a fabricated
  placeholder value.
- `route`/path-animation semantics only apply to genuine packet-flow types
  (`chat_message`, `emergency_alert`, `sos`, `telemetry`, `route_update`);
  `heartbeat`/`join`/`leave` update liveness/topology only.
- `sensors` is fully optional (`smoke_level`, `gas_level`, `temperature_c`,
  `humidity_pct`, `motion_detected`) — the Arduino panel only renders
  gauges for fields actually present, hiding the rest rather than showing
  placeholders.

## Configuration

All ports/timeouts are environment-variable overridable — see
`shared/constants.py`:

| Variable | Default | Purpose |
|---|---|---|
| `MESHMIND_TCP_PORT` | 5000 | telemetry ingest |
| `MESHMIND_WS_PORT` | 8765 | dashboard push |
| `MESHMIND_HTTP_PORT` | 8000 | REST snapshot |
| `MESHMIND_HEARTBEAT_TIMEOUT` | 15 | seconds before a quiet node is marked offline |
| `MESHMIND_CRITICAL_CONFIDENCE` | 0.90 | emergency confidence threshold for critical visual emphasis |
