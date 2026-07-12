"""
MeshMind — central in-memory state manager.

This is the ONLY component that mutates mesh state. The TCP listener feeds
it packets; the WebSocket broadcaster and HTTP API only ever read from it
(via get_snapshot() or the subscribe() pub/sub queue). The visualization
layer never touches a raw TelemetryPacket — it only ever sees state-manager
output. This is the seam described in the spec that lets future transports
(UDP, MQTT, a REST ingest endpoint) or future analytics modules get added
without touching anything downstream.

Design note on "route": per spec, path/route information is only meaningful
for genuine packet-flow messages (chat_message, emergency_alert, sos,
telemetry-relay, route_update). Heartbeats, joins, and leaves affect node
liveness and topology only — they never produce a routed "packet traveling
the mesh" animation event. NON_ROUTABLE_PACKET_TYPES enforces that boundary
in one place so it can't quietly drift as the code grows.
"""

from __future__ import annotations

import asyncio
import itertools
import time
from dataclasses import dataclass, field, asdict
from typing import Optional

from shared.schema import (
    TelemetryPacket,
    PacketType,
    DeviceType,
    ConnectionStatus,
    EmergencyClass,
    DeliveryStatus,
    GpsCoordinate,
    SensorData,
)
from shared import constants as C

# Packet types that never carry route/path animation semantics — they signal
# liveness or topology membership, not a message traveling hop-to-hop.
NON_ROUTABLE_PACKET_TYPES = frozenset({
    PacketType.HEARTBEAT,
    PacketType.JOIN,
    PacketType.LEAVE,
    PacketType.ACK,
    PacketType.BATTERY_UPDATE,
    PacketType.TOPOLOGY_UPDATE,
    PacketType.UNKNOWN,
})

_DEVICE_ROLE = {
    DeviceType.ANDROID_PHONE: "phone",
    DeviceType.ARDUINO_UNO_Q: "arduino",
    DeviceType.GATEWAY: "gateway",
    DeviceType.AI_PC: "observer",
    DeviceType.INTERNET_UPLINK: "uplink",
    DeviceType.UNKNOWN: "unknown",
}

_event_id_counter = itertools.count(1)


def _next_event_id() -> str:
    return f"evt-{next(_event_id_counter)}-{int(time.time() * 1000)}"


# --------------------------------------------------------------------------
# State records
# --------------------------------------------------------------------------

@dataclass
class NodeState:
    node_id: str
    device_type: DeviceType = DeviceType.UNKNOWN
    role: str = "unknown"
    status: ConnectionStatus = ConnectionStatus.UNKNOWN

    battery_pct: Optional[float] = None
    signal_strength_dbm: Optional[float] = None
    neighbors: list[str] = field(default_factory=list)

    emergency_class: EmergencyClass = EmergencyClass.NONE
    confidence: Optional[float] = None

    gps: Optional[GpsCoordinate] = None
    sensors: Optional[SensorData] = None

    first_seen: float = 0.0
    last_seen: float = 0.0
    last_heartbeat: float = 0.0

    packets_sent: int = 0
    packets_relayed: int = 0
    
    # Metadata for ordering and detailed inspect
    last_packet_timestamp: float = 0.0
    cpu_usage: Optional[float] = None
    memory_usage: Optional[float] = None
    ai_inference_count: int = 0

    def to_dict(self) -> dict:
        d = asdict(self)
        d["device_type"] = self.device_type.value
        d["status"] = self.status.value
        d["emergency_class"] = self.emergency_class.value
        
        # Advanced live status tags for styling
        now = time.time()
        d["is_gateway"] = self.device_type == DeviceType.GATEWAY
        d["low_battery"] = self.battery_pct is not None and self.battery_pct < 20
        d["emergency_active"] = self.emergency_class != EmergencyClass.NONE
        d["ai_active"] = self.confidence is not None and (now - self.last_seen) < 20
        
        alarm = False
        if self.sensors:
            s = self.sensors
            if (s.smoke_level and s.smoke_level > 50) or (s.gas_level and s.gas_level > 50) or (s.temperature_c and s.temperature_c > 50) or s.motion_detected:
                alarm = True
        d["sensor_alarm"] = alarm
        d["forwarding_traffic"] = self.packets_relayed > 0
        
        return d


@dataclass
class EdgeState:
    a: str
    b: str
    quality: str = "unknown"        # "healthy" | "weak" | "broken" | "unknown"
    signal_strength_dbm: Optional[float] = None
    first_seen: float = 0.0
    last_active: float = 0.0        # last time a packet was observed flowing on this edge
    comm_count: int = 0             # communication frequency count

    def key(self) -> tuple[str, str]:
        return tuple(sorted((self.a, self.b)))

    def to_dict(self) -> dict:
        return asdict(self)


def _classify_link_quality(rssi: Optional[float]) -> str:
    if rssi is None:
        return "unknown"
    if rssi >= C.RSSI_GOOD_THRESHOLD:
        return "healthy"
    if rssi >= C.RSSI_WEAK_THRESHOLD:
        return "weak"
    return "broken"


# --------------------------------------------------------------------------
# State manager
# --------------------------------------------------------------------------

class MeshStateManager:
    """
    Single source of truth for mesh state.
    Handles deduplication, preserves packet ordering, and calculates real network metrics.
    """

    def __init__(self) -> None:
        self.nodes: dict[str, NodeState] = {}
        self.edges: dict[tuple[str, str], EdgeState] = {}

        self.emergency_feed: list[dict] = []
        self.packet_log: list[dict] = []
        self.event_timeline: list[dict] = []
        self.seen_packets: set[str] = set()

        self._subscribers: set[asyncio.Queue] = set()

        self.stats = {
            "packets_processed": 0,
            "emergencies_detected": 0,
            "started_at": time.time(),
        }

    # -- pub/sub ----------------------------------------------------------

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=C.SUBSCRIBER_QUEUE_MAXSIZE)
        self._subscribers.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subscribers.discard(q)

    def _broadcast(self, event_type: str, data: dict) -> None:
        message = {"type": event_type, "data": data, "ts": time.time()}
        for q in list(self._subscribers):
            try:
                q.put_nowait(message)
            except asyncio.QueueFull:
                try:
                    q.get_nowait()
                    q.put_nowait(message)
                except asyncio.QueueEmpty:
                    pass

    # -- ingestion ----------------------------------------------------------

    def ingest_packet(self, packet: TelemetryPacket) -> None:
        now = time.time()
        self.stats["packets_processed"] += 1

        # Check for packet deduplication across multiple gateways
        is_duplicate = packet.packet_id in self.seen_packets
        if is_duplicate:
            # Reconcile connection liveness and edges even if packet log/timeline events are duplicate
            if packet.source_node_id in self.nodes:
                self.nodes[packet.source_node_id].last_seen = max(self.nodes[packet.source_node_id].last_seen, now)
            self._sync_edges_from_packet_routing(packet, now)
            # Emit a lightweight event or log to track retransmissions
            if packet.delivery_status == DeliveryStatus.RETRANSMITTED:
                self._broadcast("heartbeat_ripple", {"node_id": packet.source_node_id, "ts": now})
            return

        self.seen_packets.add(packet.packet_id)
        if len(self.seen_packets) > C.MAX_PACKET_LOG_LEN * 2:
            self.seen_packets = set(list(self.seen_packets)[-C.MAX_PACKET_LOG_LEN:])

        # Touch and update node state
        node, is_new = self._touch_node(packet, now)
        
        # Sync edges from actual routing paths, hops, and neighbor updates
        self._sync_edges_from_packet_routing(packet, now)

        if packet.packet_type == PacketType.HEARTBEAT:
            self._handle_heartbeat(node, now)
        elif packet.packet_type == PacketType.JOIN:
            self._handle_join(node, now, is_new_node=is_new)
        elif packet.packet_type == PacketType.LEAVE:
            self._handle_leave(node, now)
        elif packet.packet_type in (PacketType.SOS, PacketType.EMERGENCY):
            self._handle_emergency(packet, node, now)
        elif packet.packet_type in (PacketType.ROUTING, PacketType.TOPOLOGY_UPDATE):
            self._handle_route_update(packet, now)
        elif packet.packet_type in (PacketType.CHAT, PacketType.SENSOR, PacketType.BATTERY_UPDATE, PacketType.ACK):
            self._handle_generic_packet(packet, now)

        # Trigger events from telemetry indicators
        if packet.predicted_category and packet.predicted_category != EmergencyClass.NONE:
            node.ai_inference_count = getattr(node, 'ai_inference_count', 0) + 1
            self._emit_event(
                "ai_classification",
                node_id=packet.source_node_id,
                ts=now,
                detail={
                    "message": packet.payload if isinstance(packet.payload, str) else (packet.payload or {}).get("message"),
                    "predicted_category": packet.predicted_category.value,
                    "confidence_score": packet.confidence_score,
                    "latency_ms": packet.latency_ms
                }
            )

        if packet.delivery_status == DeliveryStatus.RETRANSMITTED:
            self._emit_event(
                "packet_retransmission",
                node_id=packet.source_node_id,
                ts=now,
                detail={"packet_id": packet.packet_id, "next_hop": packet.next_hop}
            )
        elif packet.delivery_status == DeliveryStatus.DUPLICATE_SUPPRESSED:
            self._emit_event(
                "duplicate_suppressed",
                node_id=packet.source_node_id,
                ts=now,
                detail={"packet_id": packet.packet_id}
            )

        if packet.packet_type == PacketType.ACK:
            self._emit_event(
                "packet_acknowledgement",
                node_id=packet.source_node_id,
                ts=now,
                detail={"packet_id": packet.packet_id, "destination": packet.destination}
            )

        if packet.sensors:
            s = packet.sensors
            if (s.smoke_level and s.smoke_level > 50) or (s.gas_level and s.gas_level > 50) or (s.temperature_c and s.temperature_c > 50) or s.motion_detected:
                self._emit_event(
                    "sensor_alarm",
                    node_id=packet.source_node_id,
                    ts=now,
                    detail={
                        "smoke": s.smoke_level,
                        "gas": s.gas_level,
                        "temp": s.temperature_c,
                        "motion": s.motion_detected
                    }
                )

        self._broadcast("node_update", node.to_dict())
        self._broadcast("stats", self._compute_live_stats())

    def _compute_live_stats(self) -> dict:
        now = time.time()
        active_nodes = [n for n in self.nodes.values() if n.status == ConnectionStatus.ONLINE]
        total_nodes = len(self.nodes)
        active_gateways = [n for n in active_nodes if n.device_type == DeviceType.GATEWAY]
        
        # Calculate delivery stats from packet_log
        delivered_count = sum(1 for p in self.packet_log if p.get("delivery_status") == "delivered")
        failed_count = sum(1 for p in self.packet_log if p.get("delivery_status") == "failed")
        total_delivery_packets = delivered_count + failed_count
        delivery_success_rate = (delivered_count / total_delivery_packets * 100) if total_delivery_packets > 0 else 100.0
        packet_loss_pct = (failed_count / total_delivery_packets * 100) if total_delivery_packets > 0 else 0.0
        
        # Retransmissions
        retrans_count = sum(1 for p in self.packet_log if p.get("delivery_status") == "retransmitted")
        retrans_freq = (retrans_count / len(self.packet_log) * 100) if self.packet_log else 0.0
        
        # Hop count and route length
        hop_counts = [p.get("hop_count") for p in self.packet_log if p.get("hop_count", 0) > 0]
        avg_hop_count = (sum(hop_counts) / len(hop_counts)) if hop_counts else 1.0
        
        route_lengths = [len(p.get("route", [])) for p in self.packet_log if p.get("route")]
        avg_route_len = (sum(route_lengths) / len(route_lengths)) if route_lengths else 1.0
        
        # Latency
        latencies = [p.get("latency_ms") for p in self.packet_log if p.get("latency_ms") is not None]
        avg_latency = (sum(latencies) / len(latencies)) if latencies else 45.0
        
        # Node Uptime
        uptimes = [n.last_seen - n.first_seen for n in active_nodes if n.first_seen]
        avg_uptime = (sum(uptimes) / len(uptimes)) if uptimes else 0.0
        
        # Battery distribution
        batteries = [n.battery_pct for n in active_nodes if n.battery_pct is not None]
        avg_battery = (sum(batteries) / len(batteries)) if batteries else None
        min_battery = min(batteries) if batteries else None
        max_battery = max(batteries) if batteries else None
        
        # Heatmap average RSSI
        rssis = [e.signal_strength_dbm for e in self.edges.values() if e.signal_strength_dbm is not None]
        avg_rssi = (sum(rssis) / len(rssis)) if rssis else -70.0
        
        # AI throughput
        recent_ai = sum(1 for p in self.packet_log if p.get("predicted_category") and p.get("predicted_category") != "none" and (now - p.get("timestamp", 0)) < 60)
        
        # Bandwidth
        recent_packets = sum(1 for p in self.packet_log if (now - p.get("timestamp", 0)) < 60)
        
        # Emergency propagation
        em_lats = [e.get("latency_ms") for e in self.emergency_feed if e.get("latency_ms") is not None]
        avg_em_lat = (sum(em_lats) / len(em_lats)) if em_lats else 120.0
        
        # Network Diameter
        diameter = self._calculate_network_diameter()
        
        # Routing Efficiency
        routing_efficiency = 95.0
        if avg_hop_count > 0:
            routing_efficiency = min(100.0, (diameter / avg_hop_count) * 100.0) if diameter > 0 else 100.0
        
        return {
            **self.stats,
            "active_nodes": len(active_nodes),
            "total_nodes": total_nodes,
            "active_gateways": len(active_gateways),
            "active_emergencies": len([e for e in self.emergency_feed if (now - e["timestamp"]) < C.HEARTBEAT_TIMEOUT_SECONDS * 4]),
            "delivery_success_rate": round(delivery_success_rate, 1),
            "packet_loss_percentage": round(packet_loss_pct, 1),
            "retransmission_frequency": round(retrans_freq, 1),
            "average_hop_count": round(avg_hop_count, 1),
            "average_route_length": round(avg_route_len, 1),
            "end_to_end_latency": round(avg_latency, 1),
            "node_uptime_avg": round(avg_uptime, 1),
            "battery_avg": round(avg_battery, 1) if avg_battery is not None else None,
            "battery_min": min_battery,
            "battery_max": max_battery,
            "signal_strength_avg": round(avg_rssi, 1),
            "ai_inference_throughput": recent_ai,
            "bandwidth_utilization": recent_packets,
            "emergency_propagation_time": round(avg_em_lat, 1),
            "network_diameter": diameter,
            "routing_efficiency": round(routing_efficiency, 1),
            "server_time": now,
        }

    def _calculate_network_diameter(self) -> int:
        adj = {}
        active_nodes = {nid for nid, n in self.nodes.items() if n.status == ConnectionStatus.ONLINE}
        for edge in self.edges.values():
            if edge.quality != "broken" and edge.a in active_nodes and edge.b in active_nodes:
                adj.setdefault(edge.a, []).append(edge.b)
                adj.setdefault(edge.b, []).append(edge.a)
        
        if not adj:
            return 0
        
        max_shortest_path = 0
        for start in adj:
            visited = {start: 0}
            queue = [start]
            while queue:
                curr = queue.pop(0)
                dist = visited[curr]
                max_shortest_path = max(max_shortest_path, dist)
                for neighbor in adj[curr]:
                    if neighbor not in visited:
                       visited[neighbor] = dist + 1
                       queue.append(neighbor)
        return max_shortest_path

    # -- node/edge bookkeeping ------------------------------------------

    def _touch_node(self, packet: TelemetryPacket, now: float) -> tuple[NodeState, bool]:
        node = self.nodes.get(packet.source_node_id)
        is_new = node is None
        if node is None:
            node = NodeState(node_id=packet.source_node_id, first_seen=now)
            self.nodes[packet.source_node_id] = node
            self._emit_event("node_joined", node_id=packet.source_node_id, ts=now,
                              detail={"device_type": packet.device_type.value})

        # Preserve ordering: reject stale packet field overrides
        if packet.timestamp < node.last_packet_timestamp:
            node.last_seen = max(node.last_seen, now)
            node.packets_sent += 1
            return node, is_new

        node.last_packet_timestamp = packet.timestamp

        if packet.device_type != DeviceType.UNKNOWN:
            node.device_type = packet.device_type
            node.role = _DEVICE_ROLE.get(packet.device_type, "unknown")

        if packet.status != ConnectionStatus.UNKNOWN:
            node.status = packet.status
        elif node.status in (ConnectionStatus.UNKNOWN, ConnectionStatus.OFFLINE, ConnectionStatus.DISCONNECTED):
            node.status = ConnectionStatus.ONLINE

        if packet.device_battery_pct is not None:
            node.battery_pct = packet.device_battery_pct
        if packet.signal_strength_dbm is not None:
            node.signal_strength_dbm = packet.signal_strength_dbm
        if packet.neighbors:
            node.neighbors = packet.neighbors
        if packet.predicted_category != EmergencyClass.NONE:
            node.emergency_class = packet.predicted_category
            node.confidence = packet.confidence_score
        if packet.gps is not None:
            node.gps = packet.gps
        if packet.sensors is not None:
            node.sensors = packet.sensors
            
        if packet.cpu_usage is not None:
            node.cpu_usage = packet.cpu_usage
        if packet.memory_usage is not None:
            node.memory_usage = packet.memory_usage

        node.last_seen = max(node.last_seen, now)
        node.packets_sent += 1
        return node, is_new

    def _sync_edges_from_packet_routing(self, packet: TelemetryPacket, now: float) -> None:
        # 1. Previous hop to next hop
        if packet.previous_hop and packet.next_hop:
            self._add_or_update_edge(packet.previous_hop, packet.next_hop, packet.signal_strength_dbm, now)
        
        # 2. Hops in route history
        route = packet.route_history
        if len(route) >= 2:
            for i in range(len(route) - 1):
                self._add_or_update_edge(route[i], route[i+1], None, now)
                  
        # 3. BLE neighbors / Wi-Fi peers of the source node
        for neighbor_id in packet.neighbors:
            self._add_or_update_edge(packet.source_node_id, neighbor_id, packet.signal_strength_dbm, now)

    def _add_or_update_edge(self, node_a: str, node_b: str, rssi: Optional[float], now: float) -> None:
        if node_a == node_b:
            return
        # Ensure both nodes exist
        self._touch_node_by_id(node_a, now)
        self._touch_node_by_id(node_b, now)
        
        key = tuple(sorted((node_a, node_b)))
        edge = self.edges.get(key)
        if edge is None:
            edge = EdgeState(a=key[0], b=key[1], first_seen=now)
            self.edges[key] = edge
            self._emit_event("edge_established", ts=now, detail={"a": key[0], "b": key[1]})
        
        edge.last_active = now
        edge.comm_count = getattr(edge, "comm_count", 0) + 1
        if rssi is not None:
            edge.signal_strength_dbm = rssi
            edge.quality = _classify_link_quality(rssi)
        elif edge.quality == "unknown":
            edge.quality = "healthy"
            
        self._broadcast("edge_update", edge.to_dict())

    def _touch_node_by_id(self, node_id: str, now: float) -> NodeState:
        node = self.nodes.get(node_id)
        if node is None:
            node = NodeState(node_id=node_id, first_seen=now, last_seen=now)
            self.nodes[node_id] = node
            self._emit_event("node_joined", node_id=node_id, ts=now, detail={"device_type": "unknown"})
            self._broadcast("node_update", node.to_dict())
        return node

    # -- packet-type handlers ------------------------------------------

    def _handle_heartbeat(self, node: NodeState, now: float) -> None:
        node.last_heartbeat = now
        node.status = ConnectionStatus.ONLINE
        self._broadcast("heartbeat_ripple", {"node_id": node.node_id, "ts": now})

    def _handle_join(self, node: NodeState, now: float, is_new_node: bool) -> None:
        node.status = ConnectionStatus.ONLINE
        if not is_new_node:
            self._emit_event("node_rejoined", node_id=node.node_id, ts=now,
                              detail={"device_type": node.device_type.value})

    def _handle_leave(self, node: NodeState, now: float) -> None:
        node.status = ConnectionStatus.OFFLINE
        self._emit_event("node_left", node_id=node.node_id, ts=now, detail={})
        self._broadcast("node_offline", {"node_id": node.node_id})

    def _handle_route_update(self, packet: TelemetryPacket, now: float) -> None:
        self._emit_event("route_update", node_id=packet.source_node_id, ts=now,
                          detail={"route": packet.route_history})
        self._broadcast("route_update", {
            "packet_id": packet.packet_id,
            "route": packet.route_history,
            "sender_id": packet.source_node_id,
            "destination_id": packet.destination,
        })

    def _handle_emergency(self, packet: TelemetryPacket, node: NodeState, now: float) -> None:
        is_critical = (packet.confidence_score or 0.0) >= C.CRITICAL_CONFIDENCE_THRESHOLD
        entry = {
            "id": packet.packet_id,
            "node_id": node.node_id,
            "emergency_class": packet.predicted_category.value,
            "confidence": packet.confidence_score,
            "message": packet.payload if isinstance(packet.payload, str) else (packet.payload or {}).get("message"),
            "timestamp": now,
            "critical": is_critical,
            "route": list(packet.route_history),
            "delivery_status": packet.delivery_status.value,
            "is_sos": packet.packet_type == PacketType.SOS,
            "latency_ms": packet.latency_ms,
        }
        self.emergency_feed.append(entry)
        if len(self.emergency_feed) > C.MAX_EMERGENCY_FEED_LEN:
            self.emergency_feed.pop(0)
        self.stats["emergencies_detected"] += 1

        self._emit_event(
            "sos" if packet.packet_type == PacketType.SOS else "emergency_detected",
            node_id=node.node_id, ts=now, detail=entry,
        )
        self._broadcast("emergency", entry)
        self._log_packet_event(packet, now)

    def _handle_generic_packet(self, packet: TelemetryPacket, now: float) -> None:
        self._log_packet_event(packet, now)

    def _log_packet_event(self, packet: TelemetryPacket, now: float) -> None:
        if packet.packet_type in NON_ROUTABLE_PACKET_TYPES:
            return
        if not packet.route_history and not (packet.source_node_id and packet.destination):
            return
        entry = {
            "packet_id": packet.packet_id,
            "packet_type": packet.packet_type.value,
            "sender_id": packet.source_node_id,
            "destination_id": packet.destination,
            "route": list(packet.route_history),
            "timestamp": now,
            "delivery_status": packet.delivery_status.value,
            "latency_ms": packet.latency_ms,
            "predicted_category": packet.predicted_category.value,
        }
        self.packet_log.append(entry)
        if len(self.packet_log) > C.MAX_PACKET_LOG_LEN:
            self.packet_log.pop(0)

        for hop_node_id in {entry["sender_id"], *packet.route_history}:
            hop = self.nodes.get(hop_node_id)
            if hop and hop_node_id != entry["sender_id"]:
                hop.packets_relayed += 1

        self._broadcast("packet", entry)

    # -- events / timeline ------------------------------------------------

    def _emit_event(self, event_type: str, ts: float, detail: dict, node_id: Optional[str] = None) -> None:
        entry = {
            "id": _next_event_id(),
            "event_type": event_type,
            "node_id": node_id,
            "timestamp": ts,
            "detail": detail,
        }
        self.event_timeline.append(entry)
        if len(self.event_timeline) > C.MAX_EVENT_TIMELINE_LEN:
            self.event_timeline.pop(0)
        self._broadcast("timeline_event", entry)

    # -- liveness monitor ---------------------------------------------------

    async def run_heartbeat_monitor(self) -> None:
        while True:
            await asyncio.sleep(C.HEARTBEAT_CHECK_INTERVAL_SECONDS)
            now = time.time()
            for node in self.nodes.values():
                if node.status in (ConnectionStatus.OFFLINE, ConnectionStatus.DISCONNECTED):
                    continue
                last_activity = max(node.last_seen, node.last_heartbeat)
                if last_activity and (now - last_activity) > C.HEARTBEAT_TIMEOUT_SECONDS:
                    node.status = ConnectionStatus.DISCONNECTED
                    self._emit_event("node_timed_out", node_id=node.node_id, ts=now,
                                      detail={"last_activity": last_activity})
                    self._broadcast("node_offline", {"node_id": node.node_id})
                    self._broadcast("node_update", node.to_dict())
                    self._broadcast("stats", self._compute_live_stats())

    # -- snapshot ---------------------------------------------------------

    def get_snapshot(self) -> dict:
        return {
            "nodes": {nid: n.to_dict() for nid, n in self.nodes.items()},
            "edges": [e.to_dict() for e in self.edges.values()],
            "emergency_feed": list(self.emergency_feed),
            "packet_log": list(self.packet_log),
            "event_timeline": list(self.event_timeline),
            "stats": self._compute_live_stats(),
        }
