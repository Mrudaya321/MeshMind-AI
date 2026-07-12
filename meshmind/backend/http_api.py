"""
MeshMind — HTTP state API.

A minimal REST surface alongside the WebSocket stream:

  GET /api/state   -> full current snapshot (same shape as the WS "snapshot"
                       message) — useful for the initial Streamlit page load,
                       debugging with curl, or any future consumer that
                       doesn't want to hold a persistent socket open.
  GET /health       -> liveness probe for the backend process itself.

This layer, like the WS broadcaster, only ever reads from the state
manager — it never mutates state and never touches raw telemetry packets.
"""

from __future__ import annotations

import asyncio
import logging

from aiohttp import web

from shared import constants as C
from backend.state_manager import MeshStateManager

logger = logging.getLogger("meshmind.http_api")


def build_app(state_manager: MeshStateManager) -> web.Application:
    app = web.Application()

    async def get_state(request: web.Request) -> web.Response:
        return web.json_response(state_manager.get_snapshot())

    async def health(request: web.Request) -> web.Response:
        return web.json_response({"status": "ok"})

    # Permissive CORS: the Streamlit frontend and this API commonly run on
    # different ports/hosts (e.g. Streamlit on 8501, this API on 8000), and
    # this is a closed demo network, not a public multi-tenant service.
    @web.middleware
    async def cors_middleware(request, handler):
        if request.method == "OPTIONS":
            resp = web.Response()
        else:
            resp = await handler(request)
        resp.headers["Access-Control-Allow-Origin"] = "*"
        resp.headers["Access-Control-Allow-Methods"] = "GET, OPTIONS"
        resp.headers["Access-Control-Allow-Headers"] = "*"
        return resp

    app.middlewares.append(cors_middleware)
    app.router.add_get("/api/state", get_state)
    app.router.add_get("/health", health)
    app.router.add_route("OPTIONS", "/api/state", lambda r: web.Response())
    return app


async def run_http_api(state_manager: MeshStateManager, host: str = C.HTTP_LISTEN_HOST,
                        port: int = C.HTTP_LISTEN_PORT) -> None:
    app = build_app(state_manager)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host, port)
    await site.start()
    logger.info("HTTP state API up on http://%s:%d", host, port)
    await asyncio.Future()  # run until cancelled
