"""
MeshMind — TCP telemetry listener.

Every mesh node (or the gateway relaying on their behalf) opens a TCP
connection to this server and writes one JSON object per line
(newline-delimited JSON / NDJSON). This listener does nothing but:

  1. accept connections,
  2. split incoming bytes into lines,
  3. parse each line into a TelemetryPacket,
  4. hand it to the state manager.

It never touches visualization state directly, and a malformed line from
one device never affects other connections — we log and drop the bad line,
we don't close the connection or crash the server, since a single garbled
packet from a flaky BLE/WiFi-Direct hop is expected, not exceptional.
"""

from __future__ import annotations

import asyncio
import logging

from shared import constants as C
from shared.schema import TelemetryPacket, PacketValidationError
from backend.state_manager import MeshStateManager

logger = logging.getLogger("meshmind.tcp_listener")


class TcpTelemetryListener:
    def __init__(self, state_manager: MeshStateManager, host: str = C.TCP_LISTEN_HOST,
                 port: int = C.TCP_LISTEN_PORT) -> None:
        self.state_manager = state_manager
        self.host = host
        self.port = port
        self._server: asyncio.AbstractServer | None = None

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle_connection, self.host, self.port)
        addrs = ", ".join(str(sock.getsockname()) for sock in self._server.sockets)
        logger.info("TCP telemetry listener up on %s", addrs)

    async def serve_forever(self) -> None:
        if self._server is None:
            await self.start()
        async with self._server:
            await self._server.serve_forever()

    async def _handle_connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        logger.info("connection opened from %s", peer)
        try:
            while True:
                try:
                    line = await reader.readuntil(C.TCP_LINE_DELIMITER)
                except asyncio.LimitOverrunError:
                    # A line longer than the stream's internal buffer limit —
                    # drain and drop it rather than let it wedge the connection.
                    logger.warning("oversized line from %s, dropping connection", peer)
                    break
                except asyncio.IncompleteReadError as e:
                    # Connection closed, possibly with a trailing partial line.
                    if e.partial:
                        self._process_line(e.partial, peer)
                    break

                if len(line) > C.TCP_MAX_LINE_BYTES:
                    logger.warning("line from %s exceeds %d bytes, dropping", peer, C.TCP_MAX_LINE_BYTES)
                    continue

                self._process_line(line, peer)
        except (ConnectionResetError, asyncio.IncompleteReadError):
            pass
        finally:
            logger.info("connection closed from %s", peer)
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    def _process_line(self, line: bytes, peer) -> None:
        if not line.strip():
            return
        try:
            packet = TelemetryPacket.from_json_line(line)
        except PacketValidationError as e:
            logger.warning("dropped malformed packet from %s: %s", peer, e)
            return
        except Exception:
            logger.exception("unexpected error parsing packet from %s", peer)
            return

        try:
            self.state_manager.ingest_packet(packet)
        except Exception:
            # A bug in ingest logic must never take down the listener —
            # log it loudly so it gets fixed, but keep the pipeline alive.
            logger.exception("state manager failed to ingest packet from %s (node_id=%s)",
                              peer, packet.node_id)
