"""
client.py - Lightweight IPC client sending command envelopes to the Android System Gateway.

Authentication: the gateway requires `Authorization: Bearer <token>`. The token
is a per-process random value the app writes to the app-private
`logs/gateway.token` file (same UID only); this client reads it the same way
it reads `logs/gateway.port`.
"""
import json
import os
import urllib.request
import urllib.error
from typing import Dict, Any

# Fallback ports must match GATEWAY_PORT / GATEWAY_PORT_DEV in config/app.env.
GATEWAY_PORT = 45170
GATEWAY_PORT_DEV = 45171


def _candidate_log_dirs():
    dirs = []
    home = os.environ.get("HOME")
    if home:
        dirs.append(os.path.join(home, "logs"))

    try:
        from ..context import path_resolver
        app_root = path_resolver.load_paths().get("app_root")
        if app_root:
            dirs.append(os.path.join(app_root, "logs"))
    except Exception:
        pass

    dirs.extend([
        "/data/user/0/io.github.ziyekaixuan.antigravity.dev/files/logs",
        "/data/user/0/io.github.ziyekaixuan.antigravity/files/logs",
    ])
    return dirs


def _read_log_file(name: str):
    for d in _candidate_log_dirs():
        p = os.path.join(d, name)
        if os.path.isfile(p):
            try:
                with open(p, "r") as f:
                    value = f.read().strip()
                    if value:
                        return value
            except Exception:
                pass
    return None


def _is_dev() -> bool:
    return (
        os.environ.get("DEV_MODE") == "1"
        or os.environ.get("AGY_APPLICATION_ID") == "io.github.ziyekaixuan.antigravity.dev"
        or "io.github.ziyekaixuan.antigravity.dev" in (os.environ.get("HOME") or "")
    )


def resolve_gateway_url() -> str:
    if "AGY_GATEWAY_URL" in os.environ:
        return os.environ["AGY_GATEWAY_URL"]

    port = _read_log_file("gateway.port")
    if port and port.isdigit():
        return f"http://127.0.0.1:{port}/gateway"

    return (
        f"http://127.0.0.1:{GATEWAY_PORT_DEV}/gateway"
        if _is_dev()
        else f"http://127.0.0.1:{GATEWAY_PORT}/gateway"
    )


def resolve_gateway_token():
    token = os.environ.get("AGY_GATEWAY_TOKEN")
    if token:
        return token
    return _read_log_file("gateway.token")


DEFAULT_GATEWAY_URL = resolve_gateway_url()


def _build_request(url: str, data: bytes):
    headers = {"Content-Type": "application/json"}
    token = resolve_gateway_token()
    if token:
        headers["Authorization"] = "Bearer " + token
    return urllib.request.Request(url, data=data, headers=headers)


def send_command(command: str, payload: Dict[str, Any] = None, gateway_url: str = None) -> Dict[str, Any]:
    url = gateway_url or resolve_gateway_url()
    packet = {
        "command": command,
        "payload": payload or {}
    }
    data = json.dumps(packet).encode("utf-8")
    req = _build_request(url, data)

    try:
        with urllib.request.urlopen(req, timeout=1.0) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, OSError) as e:
        # If default url was used, try the alternate port (release <-> dev).
        if gateway_url is None:
            alt_port = GATEWAY_PORT if f":{GATEWAY_PORT_DEV}/" in url else GATEWAY_PORT_DEV
            alt_url = f"http://127.0.0.1:{alt_port}/gateway"
            try:
                alt_req = _build_request(alt_url, data)
                with urllib.request.urlopen(alt_req, timeout=1.0) as resp:
                    return json.loads(resp.read().decode("utf-8"))
            except Exception:
                pass
        return {"ok": False, "fallback": True, "error": str(e), "packet": packet}
