"""
MeshMind — WebSocket broadcaster.

This is the bridge between the state manager and the frontend. Every
connecting client (the Streamlit dashboard's embedded D3 component) gets:

  1. one "snapshot" message containing the full current state, so a client
     that connects mid-demo isn't starting from a blank graph, then
  2. a live stream of incremental events (node_update, edge_update,
     emergency, packet, timeline_event, heartbeat_ripple, node_offline,
     route_update) exactly as they're produced by MeshStateManager.

The broadcaster does no interpretation of these events — it is a dumb pipe
from state_manager._broadcast() to the wire. All animation/rendering
decisions live in the frontend, keeping this layer trivial and stable.
"""

from __future__ import annotations

import asyncio
import json
import logging

import websockets
from websockets.server import WebSocketServerProtocol

from shared import constants as C
from backend.state_manager import MeshStateManager

logger = logging.getLogger("meshmind.ws_broadcaster")


class WebSocketBroadcaster:
    def __init__(self, state_manager: MeshStateManager, host: str = C.WS_LISTEN_HOST,
                 port: int = C.WS_LISTEN_PORT) -> None:
        self.state_manager = state_manager
        self.host = host
        self.port = port

    async def _handle_client(self, ws: WebSocketServerProtocol) -> None:
        peer = ws.remote_address
        logger.info("dashboard client connected from %s", peer)

        queue = self.state_manager.subscribe()
        try:
            snapshot = self.state_manager.get_snapshot()
            await ws.send(json.dumps({"type": "snapshot", "data": snapshot}))

            # Two concurrent jobs on this connection: forward outbound
            # events from the state manager, and drain/ignore any inbound
            # messages (the dashboard is a read-only observer, but we still
            # need to consume the socket so ping/pong and close frames work).
            forward_task = asyncio.create_task(self._forward_events(ws, queue))
            drain_task = asyncio.create_task(self._drain_inbound(ws))

            done, pending = await asyncio.wait(
                {forward_task, drain_task}, return_when=asyncio.FIRST_COMPLETED
            )
            for task in pending:
                task.cancel()
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.state_manager.unsubscribe(queue)
            logger.info("dashboard client disconnected from %s", peer)

    async def _forward_events(self, ws: WebSocketServerProtocol, queue: asyncio.Queue) -> None:
        while True:
            message = await queue.get()
            await ws.send(json.dumps(message))

    async def _drain_inbound(self, ws: WebSocketServerProtocol) -> None:
        async for _ in ws:
            pass  # dashboard doesn't send control messages (yet); ignore silently

    async def serve_forever(self) -> None:
        async with websockets.serve(self._handle_client, self.host, self.port,
                                     ping_interval=20, ping_timeout=20):
            logger.info("WebSocket broadcaster up on ws://%s:%d", self.host, self.port)
            await asyncio.Future()  # run until cancelled
