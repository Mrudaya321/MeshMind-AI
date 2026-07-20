"""
MeshMind — Streamlit entrypoint.

This file is deliberately thin. The Streamlit process's only jobs are:
  1. let the operator configure which backend WebSocket endpoint to observe
     (useful when the backend runs on a different machine/port than the
     default), and
  2. embed the self-contained mesh_dashboard.html component.

Everything else — the force-directed graph, live counters, packet
animations, emergency feed — runs entirely inside that embedded component
via its own WebSocket connection to the backend. It does not depend on
Streamlit's script-rerun cycle at all, which is what keeps its animations
smooth instead of restarting on every rerun.

Run with:
    streamlit run frontend/app.py
"""

from __future__ import annotations

import os
from pathlib import Path

import streamlit as st
import streamlit.components.v1 as components

DEFAULT_WS_HOST = os.environ.get("MESHMIND_WS_HOST_PUBLIC", "localhost")
DEFAULT_WS_PORT = int(os.environ.get("MESHMIND_WS_PORT", 8765))

st.set_page_config(
    page_title="MeshMind Observer",
    page_icon="🛰️",
    layout="wide",
    initial_sidebar_state="collapsed",
)

# Minimal page-level styling so the embedded component's dark theme isn't
# boxed in by Streamlit's default light chrome around it.
st.markdown("""
<style>
  .block-container { padding-top: 1rem; padding-bottom: 0rem; max-width: 100% !important; }
  header[data-testid="stHeader"] { background: #0a0e16; }
  #MainMenu, footer { visibility: hidden; }
</style>
""", unsafe_allow_html=True)

with st.sidebar:
    st.markdown("### MeshMind connection")
    st.caption("Point this at the machine running `backend/server.py`.")
    ws_host = st.text_input("Backend host", value=DEFAULT_WS_HOST)
    ws_port = st.number_input("WebSocket port", value=DEFAULT_WS_PORT, step=1)
    st.caption(
        "The dashboard connects directly to this WebSocket endpoint from your "
        "browser — it does not proxy telemetry through Streamlit itself."
    )

ws_url = f"ws://{ws_host}:{int(ws_port)}"

template_path = Path(__file__).parent / "components" / "mesh_dashboard.html"
html_template = template_path.read_text(encoding="utf-8")
html = html_template.replace("__WS_URL__", ws_url)

components.html(html, height=1400, scrolling=True)
