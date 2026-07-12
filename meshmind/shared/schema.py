"""
MeshMind — telemetry protocol schema.

This module defines what a valid packet looks like, reflecting the actual
MeshMind communication protocol used inside the Android and Arduino nodes.
It is highly tolerant of missing fields and ignores unknown fields.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Any, Optional


class PacketValidationError(ValueError):
    """Raised when a raw payload cannot be turned into a valid TelemetryPacket."""


# --------------------------------------------------------------------------
# Enums — Soft enums for robust forward-compatibility
# --------------------------------------------------------------------------

class PacketType(str, Enum):
    HEARTBEAT = "heartbeat"
    ROUTING = "routing"
    EMERGENCY = "emergency"
    SOS = "sos"
    CHAT = "chat"
    SENSOR = "sensor"
    ACK = "ack"
    JOIN = "join"
    LEAVE = "leave"
    BATTERY_UPDATE = "battery_update"
    TOPOLOGY_UPDATE = "topology_update"
    UNKNOWN = "unknown"

    @classmethod
    def _missing_(cls, value):
        return cls.UNKNOWN


class DeviceType(str, Enum):
    ANDROID_PHONE = "android_phone"
    ARDUINO_UNO_Q = "arduino_uno_q"
    GATEWAY = "gateway"
    AI_PC = "ai_pc"
    INTERNET_UPLINK = "internet_uplink"
    UNKNOWN = "unknown"

    @classmethod
    def _missing_(cls, value):
        return cls.UNKNOWN


class ConnectionStatus(str, Enum):
    ONLINE = "online"
    WEAK = "weak"
    OFFLINE = "offline"
    DISCONNECTED = "disconnected"
    UNKNOWN = "unknown"

    @classmethod
    def _missing_(cls, value):
        return cls.UNKNOWN


class EmergencyClass(str, Enum):
    FIRE = "fire"
    FLOOD = "flood"
    EARTHQUAKE = "earthquake"
    STORM = "storm"
    MEDICAL_EMERGENCY = "medical_emergency"
    SECURITY_THREAT = "security_threat"
    BUILDING_COLLAPSE = "building_collapse"
    INFRASTRUCTURE_FAILURE = "infrastructure_failure"
    NONE = "none"

    @classmethod
    def _missing_(cls, value):
        return cls.NONE


class DeliveryStatus(str, Enum):
    IN_TRANSIT = "in_transit"
    DELIVERED = "delivered"
    FAILED = "failed"
    RETRANSMITTED = "retransmitted"
    DUPLICATE_SUPPRESSED = "duplicate_suppressed"
    UNKNOWN = "unknown"

    @classmethod
    def _missing_(cls, value):
        return cls.UNKNOWN


def _coerce_enum(enum_cls, value, default):
    if value is None:
        return default
    if isinstance(value, enum_cls):
        return value
    try:
        return enum_cls(str(value).strip().lower())
    except Exception:
        return default


def _coerce_float(value: Any) -> Optional[float]:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _coerce_str_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        return [str(v) for v in value]
    return [str(value)]


@dataclass
class GpsCoordinate:
    latitude: float
    longitude: float
    accuracy_m: Optional[float] = None

    @classmethod
    def from_raw(cls, raw: Any) -> Optional["GpsCoordinate"]:
        if not raw or not isinstance(raw, dict):
            return None
        lat = _coerce_float(raw.get("latitude", raw.get("lat")))
        lon = _coerce_float(raw.get("longitude", raw.get("lon", raw.get("lng"))))
        if lat is None or lon is None:
            return None
        return cls(latitude=lat, longitude=lon, accuracy_m=_coerce_float(raw.get("accuracy_m")))


@dataclass
class SensorData:
    smoke_level: Optional[float] = None
    gas_level: Optional[float] = None
    temperature_c: Optional[float] = None
    humidity_pct: Optional[float] = None
    motion_detected: Optional[bool] = None

    @classmethod
    def from_raw(cls, raw: Any) -> Optional["SensorData"]:
        if not raw or not isinstance(raw, dict):
            return None
        sd = cls(
            smoke_level=_coerce_float(raw.get("smoke_level")),
            gas_level=_coerce_float(raw.get("gas_level")),
            temperature_c=_coerce_float(raw.get("temperature_c")),
            humidity_pct=_coerce_float(raw.get("humidity_pct")),
            motion_detected=raw.get("motion_detected") if raw.get("motion_detected") is not None else None,
        )
        if all(v is None for v in asdict(sd).values()):
            return None
        return sd


@dataclass
class TelemetryPacket:
    """
    The canonical representation of one real MeshMind protocol telemetry packet.
    """
    # Required base headers
    packet_id: str
    packet_type: PacketType
    source_node_id: str
    timestamp: float
    device_type: DeviceType

    # Mesh packet header fields
    previous_hop: Optional[str] = None
    next_hop: Optional[str] = None
    destination: Optional[str] = None
    hop_count: int = 0
    max_ttl: int = 0
    route_history: list[str] = field(default_factory=list)
    delivery_status: DeliveryStatus = DeliveryStatus.UNKNOWN
    packet_priority: int = 0
    payload: Any = None

    # AI classification details (from on-device MiniLM ONNX)
    predicted_category: Optional[EmergencyClass] = EmergencyClass.NONE
    confidence_score: Optional[float] = None
    inference_timestamp: Optional[float] = None

    # Arduino sensor details
    smoke_level: Optional[float] = None
    gas_concentration: Optional[float] = None
    temperature: Optional[float] = None
    humidity: Optional[float] = None
    vibration: Optional[float] = None
    sos_button_state: Optional[bool] = None
    gps_latitude: Optional[float] = None
    gps_longitude: Optional[float] = None

    # Gateway/telemetry metadata
    gateway_id: Optional[str] = None
    received_timestamp: Optional[float] = None
    device_battery_pct: Optional[float] = None
    ble_neighbors: list[str] = field(default_factory=list)
    wifi_direct_peers: list[str] = field(default_factory=list)
    cpu_usage: Optional[float] = None
    memory_usage: Optional[float] = None
    latency_ms: Optional[float] = None

    status: ConnectionStatus = ConnectionStatus.UNKNOWN
    signal_strength_dbm: Optional[float] = None
    extra: dict = field(default_factory=dict)

    # -- compatibility properties for state manager / frontend D3 --------------

    @property
    def node_id(self) -> str:
        return self.source_node_id

    @property
    def neighbors(self) -> list[str]:
        # Merge BLE neighbors and Wi-Fi peers for the topology graph links
        return list(dict.fromkeys(self.ble_neighbors + self.wifi_direct_peers))

    @property
    def route(self) -> list[str]:
        return self.route_history

    @property
    def battery_pct(self) -> Optional[float]:
        return self.device_battery_pct

    @property
    def emergency_class(self) -> EmergencyClass:
        return self.predicted_category or EmergencyClass.NONE

    @property
    def confidence(self) -> Optional[float]:
        return self.confidence_score

    @property
    def gps(self) -> Optional[GpsCoordinate]:
        if self.gps_latitude is not None and self.gps_longitude is not None:
            return GpsCoordinate(latitude=self.gps_latitude, longitude=self.gps_longitude)
        return None

    @property
    def sensors(self) -> Optional[SensorData]:
        sd = SensorData(
            smoke_level=self.smoke_level,
            gas_level=self.gas_concentration,
            temperature_c=self.temperature,
            humidity_pct=self.humidity,
            motion_detected=self.sos_button_state,  # Map SOS button to motion or inspect directly
        )
        if all(v is None for v in asdict(sd).values()):
            return None
        return sd

    # -- construction -------------------------------------------------

    @classmethod
    def from_dict(cls, raw: dict) -> "TelemetryPacket":
        if not isinstance(raw, dict):
            raise PacketValidationError(f"packet is not a JSON object: {type(raw)!r}")

        # Accept source_node_id or node_id for robustness
        source_node_id = raw.get("source_node_id") or raw.get("node_id")
        if not source_node_id:
            raise PacketValidationError("missing required field 'source_node_id' or 'node_id'")

        packet_type = _coerce_enum(PacketType, raw.get("packet_type"), PacketType.UNKNOWN)
        device_type = _coerce_enum(DeviceType, raw.get("device_type"), DeviceType.UNKNOWN)
        status = _coerce_enum(ConnectionStatus, raw.get("status"), ConnectionStatus.ONLINE)
        predicted_category = _coerce_enum(EmergencyClass, raw.get("predicted_category") or raw.get("emergency_class"), EmergencyClass.NONE)
        delivery_status = _coerce_enum(DeliveryStatus, raw.get("delivery_status"), DeliveryStatus.UNKNOWN)

        ts = _coerce_float(raw.get("timestamp"))
        timestamp = ts if ts is not None else time.time()

        known_keys = {
            "packet_id", "packet_type", "source_node_id", "node_id", "timestamp", "device_type",
            "previous_hop", "next_hop", "destination", "hop_count", "max_ttl", "route_history",
            "route", "delivery_status", "packet_priority", "payload", "predicted_category",
            "emergency_class", "confidence_score", "confidence", "inference_timestamp", "smoke_level",
            "gas_concentration", "temperature", "humidity", "vibration", "sos_button_state",
            "gps_latitude", "gps_longitude", "gateway_id", "received_timestamp", "device_battery_pct",
            "battery", "battery_pct", "ble_neighbors", "neighbors", "wifi_direct_peers", "cpu_usage",
            "memory_usage", "latency_ms", "status", "signal_strength_dbm", "signal_strength",
        }
        extra = {k: v for k, v in raw.items() if k not in known_keys}

        # Handle backward compatible neighbor mapping if present
        ble_neighbors = _coerce_str_list(raw.get("ble_neighbors") or raw.get("neighbors"))
        wifi_direct_peers = _coerce_str_list(raw.get("wifi_direct_peers"))

        return cls(
            packet_id=str(raw.get("packet_id") or f"{source_node_id}-{timestamp}"),
            packet_type=packet_type,
            source_node_id=str(source_node_id),
            timestamp=timestamp,
            device_type=device_type,
            previous_hop=raw.get("previous_hop"),
            next_hop=raw.get("next_hop"),
            destination=raw.get("destination"),
            hop_count=int(raw.get("hop_count") or 0),
            max_ttl=int(raw.get("max_ttl") or 0),
            route_history=_coerce_str_list(raw.get("route_history") or raw.get("route")),
            delivery_status=delivery_status,
            packet_priority=int(raw.get("packet_priority") or 0),
            payload=raw.get("payload"),
            predicted_category=predicted_category,
            confidence_score=_coerce_float(raw.get("confidence_score") or raw.get("confidence")),
            inference_timestamp=_coerce_float(raw.get("inference_timestamp")),
            smoke_level=_coerce_float(raw.get("smoke_level")),
            gas_concentration=_coerce_float(raw.get("gas_concentration")),
            temperature=_coerce_float(raw.get("temperature")),
            humidity=_coerce_float(raw.get("humidity")),
            vibration=_coerce_float(raw.get("vibration")),
            sos_button_state=bool(raw["sos_button_state"]) if raw.get("sos_button_state") is not None else None,
            gps_latitude=_coerce_float(raw.get("gps_latitude")),
            gps_longitude=_coerce_float(raw.get("gps_longitude")),
            gateway_id=raw.get("gateway_id"),
            received_timestamp=_coerce_float(raw.get("received_timestamp") or time.time()),
            device_battery_pct=_coerce_float(raw.get("device_battery_pct") or raw.get("battery_pct") or raw.get("battery")),
            ble_neighbors=ble_neighbors,
            wifi_direct_peers=wifi_direct_peers,
            cpu_usage=_coerce_float(raw.get("cpu_usage")),
            memory_usage=_coerce_float(raw.get("memory_usage")),
            latency_ms=_coerce_float(raw.get("latency_ms")),
            status=status,
            signal_strength_dbm=_coerce_float(raw.get("signal_strength_dbm") or raw.get("signal_strength")),
            extra=extra,
        )

    @classmethod
    def from_json_line(cls, line: bytes | str) -> "TelemetryPacket":
        if isinstance(line, bytes):
            line = line.decode("utf-8", errors="replace")
        line = line.strip()
        if not line:
            raise PacketValidationError("empty line")
        try:
            raw = json.loads(line)
        except json.JSONDecodeError as e:
            raise PacketValidationError(f"invalid JSON: {e}") from e
        return cls.from_dict(raw)

    # -- serialization --------------------------------------------------

    def to_dict(self) -> dict:
        # Merge properties into serialized dictionary so that JSON representations
        # sent to the frontend contain the flat fields the UI expects.
        d = asdict(self)
        d["node_id"] = self.node_id
        d["neighbors"] = self.neighbors
        d["route"] = self.route
        d["battery_pct"] = self.battery_pct
        d["emergency_class"] = self.emergency_class.value
        d["confidence"] = self.confidence
        d["packet_type"] = self.packet_type.value
        d["device_type"] = self.device_type.value
        d["status"] = self.status.value
        d["delivery_status"] = self.delivery_status.value

        gps = self.gps
        d["gps"] = asdict(gps) if gps else None

        sensors = self.sensors
        d["sensors"] = asdict(sensors) if sensors else None

        return d

    def to_json(self) -> str:
        return json.dumps(self.to_dict())

