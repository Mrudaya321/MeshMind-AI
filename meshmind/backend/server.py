"""
MeshMind — backend entrypoint.

Starts, on one asyncio event loop, all four backend jobs sharing a single
MeshStateManager instance:

  - TCP telemetry listener  (real mesh nodes / gateway -> ingest_packet)
  - WebSocket broadcaster   (-> Streamlit/D3 dashboard, live push)
  - HTTP state API          (-> polling/debug snapshot)
  - Heartbeat monitor       (marks stale nodes offline)

Run with:

    python -m backend.server

Every port is configurable via environment variables (see shared/constants.py)
so this can run alongside a real gateway device or be pointed at different
ports without touching code, e.g.:

    MESHMIND_TCP_PORT=6000 MESHMIND_WS_PORT=9000 python -m backend.server
"""

from __future__ import annotations

import asyncio
import argparse
import logging
import signal

from shared import constants as C
from backend.state_manager import MeshStateManager
from backend.tcp_listener import TcpTelemetryListener
from backend.ws_broadcaster import WebSocketBroadcaster
from backend.http_api import run_http_api

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("meshmind.server")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="MeshMind observer backend")
    parser.add_argument("--tcp-port", type=int, default=C.TCP_LISTEN_PORT)
    parser.add_argument("--ws-port", type=int, default=C.WS_LISTEN_PORT)
    parser.add_argument("--http-port", type=int, default=C.HTTP_LISTEN_PORT)
    parser.add_argument("--host", type=str, default="0.0.0.0",
                         help="bind host for all three servers")
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    state_manager = MeshStateManager()

    tcp_listener = TcpTelemetryListener(state_manager, host=args.host, port=args.tcp_port)
    ws_broadcaster = WebSocketBroadcaster(state_manager, host=args.host, port=args.ws_port)

    logger.info("Starting MeshMind backend...")
    logger.info("  TCP telemetry : tcp://%s:%d  (NDJSON, one packet per line)", args.host, args.tcp_port)
    logger.info("  WebSocket     : ws://%s:%d   (dashboard push)", args.host, args.ws_port)
    logger.info("  HTTP state API: http://%s:%d/api/state", args.host, args.http_port)

    tasks = [
        asyncio.create_task(tcp_listener.serve_forever(), name="tcp_listener"),
        asyncio.create_task(ws_broadcaster.serve_forever(), name="ws_broadcaster"),
        asyncio.create_task(run_http_api(state_manager, host=args.host, port=args.http_port), name="http_api"),
        asyncio.create_task(state_manager.run_heartbeat_monitor(), name="heartbeat_monitor"),
    ]

    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _request_shutdown() -> None:
        logger.info("shutdown signal received")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _request_shutdown)
        except NotImplementedError:
            # add_signal_handler isn't available on Windows' default loop
            pass

    done, pending = await asyncio.wait(
        [*tasks, asyncio.create_task(stop_event.wait())],
        return_when=asyncio.FIRST_COMPLETED,
    )

    # If we got here because stop_event fired, cancel the service tasks.
    # If we got here because a service task crashed, surface that loudly —
    # a dead TCP listener with a live dashboard is worse than a hard stop.
    for task in tasks:
        if task in done and task.exception() is not None:
            logger.error("task %s crashed: %r", task.get_name(), task.exception())

    for task in pending:
        task.cancel()
    await asyncio.gather(*pending, return_exceptions=True)
    logger.info("MeshMind backend stopped.")


if __name__ == "__main__":
    asyncio.run(main())
