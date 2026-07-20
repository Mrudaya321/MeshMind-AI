"""
MeshMind — shared constants and configuration defaults.

Every one of these can be overridden via environment variables or CLI flags
in server.py; the values here are just sane defaults for a hackathon demo
running on a single AI PC.
"""

import os

# --- Network endpoints -------------------------------------------------

TCP_LISTEN_HOST = os.environ.get("MESHMIND_TCP_HOST", "0.0.0.0")
TCP_LISTEN_PORT = int(os.environ.get("MESHMIND_TCP_PORT", 5000))

WS_LISTEN_HOST = os.environ.get("MESHMIND_WS_HOST", "0.0.0.0")
WS_LISTEN_PORT = int(os.environ.get("MESHMIND_WS_PORT", 8765))

HTTP_LISTEN_HOST = os.environ.get("MESHMIND_HTTP_HOST", "0.0.0.0")
HTTP_LISTEN_PORT = int(os.environ.get("MESHMIND_HTTP_PORT", 8000))

# --- Protocol framing ----------------------------------------------------
# TCP telemetry is newline-delimited JSON (NDJSON). One packet per line.
# This keeps the parser trivial and lets any device (Android, Arduino,
# a gateway relay script) speak the protocol with nothing more than a
# socket and a JSON encoder.
TCP_LINE_DELIMITER = b"\n"
TCP_MAX_LINE_BYTES = 64 * 1024  # guard against a runaway/garbage connection

# --- Liveness ------------------------------------------------------------
# If a node hasn't sent ANY packet (heartbeat or otherwise) within this
# many seconds, the state manager marks it offline. Checked on a background
# tick, not tied to any particular packet arrival.
HEARTBEAT_TIMEOUT_SECONDS = float(os.environ.get("MESHMIND_HEARTBEAT_TIMEOUT", 15))
HEARTBEAT_CHECK_INTERVAL_SECONDS = 2.0

# Signal-strength / link-quality thresholds (RSSI in dBm, typical BLE/WiFi range)
RSSI_GOOD_THRESHOLD = -70   # >= this -> healthy/green link
RSSI_WEAK_THRESHOLD = -85   # >= this (but < good) -> weak/yellow link
                            # below this -> considered broken/red

# --- Emergency classification ---------------------------------------------
CRITICAL_CONFIDENCE_THRESHOLD = float(os.environ.get("MESHMIND_CRITICAL_CONFIDENCE", 0.90))

# --- State manager bounds (avoid unbounded memory growth over a long demo) --
MAX_EMERGENCY_FEED_LEN = 500
MAX_PACKET_LOG_LEN = 2000
MAX_EVENT_TIMELINE_LEN = 1000

# --- Pub/sub -------------------------------------------------------------
# Max queued state-update events per connected WebSocket subscriber before
# we drop the slowest client's oldest events rather than block the whole
# broadcaster on one stuck consumer.
SUBSCRIBER_QUEUE_MAXSIZE = 256
