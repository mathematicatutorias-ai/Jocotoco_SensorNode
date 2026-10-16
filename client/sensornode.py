#!/usr/bin/env python3
"""Cliente de Jocotoco SensorNode (Windows/Linux; Python 3.8+; stdlib)."""
import argparse
import getpass
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


def call(base, action, token=None, method="GET", timeout=75, binary=False):
    headers = {"User-Agent": "Jocotoco-SensorNode-CLI/0.1.0"}
    if token:
        headers["X-SensorNode-Token"] = token
    request = Request(base + action, headers=headers, method=method, data=b"" if method == "POST" else None)
    try:
        with urlopen(request, timeout=timeout) as response:
            payload = response.read()
            return payload if binary else json.loads(payload.decode("utf-8"))
    except HTTPError as exc:
        msg = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {exc.code}: {msg}") from exc
    except URLError as exc:
        raise SystemExit(f"No se pudo conectar a SensorNode: {exc}. ¿Está abierto el túnel SSH y la APK?") from exc


def safe_name(value):
    if not value or value in (".", "..") or not all(c.isascii() and (c.isalnum() or c in "._-") for c in value):
        raise SystemExit("Nombre de archivo inválido")
    return value


def download(base, token, name, output):
    filename = safe_name(name)
    output.mkdir(parents=True, exist_ok=True)
    data = call(base, "/v1/file?name=" + quote(filename), token, binary=True)
    dest = output / filename
    # No sobrescribir archivos anteriores silenciosamente.
    if dest.exists():
        raise SystemExit(f"El archivo ya existe; no sobrescribí: {dest}")
    dest.write_bytes(data)
    print(f"Descargado: {dest} ({len(data):,} bytes)")


def main():
    parser = argparse.ArgumentParser(description="Control del Samsung J1 mediante túnel SSH y SensorNode")
    parser.add_argument("command", choices=["health", "status", "sensors", "photo", "audio", "files", "download"])
    parser.add_argument("name", nargs="?", help="Nombre de archivo para 'download'")
    parser.add_argument("--seconds", type=int, default=5, help="Duración del audio (1–60 segundos)")
    parser.add_argument("--facing", choices=["back", "front"], default="back", help="Cámara para fotografías")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--out", type=Path, default=Path("capturas_j1"))
    args = parser.parse_args()
    base = f"http://{args.host}:{args.port}"
    token = None if args.command == "health" else os.environ.get("JOCOTOCO_TOKEN") or getpass.getpass("Token de SensorNode (48 caracteres): ").strip()
    if args.command == "health":
        result = call(base, "/v1/health")
    elif args.command == "status":
        result = call(base, "/v1/status", token)
    elif args.command == "sensors":
        result = call(base, "/v1/sensors", token)
    elif args.command == "files":
        result = call(base, "/v1/files", token)
    elif args.command == "photo":
        result = call(base, "/v1/photo?facing=" + args.facing, token, method="POST")
    elif args.command == "audio":
        result = call(base, "/v1/audio?seconds=" + str(args.seconds), token, method="POST", timeout=args.seconds + 30)
    elif args.command == "download":
        if not args.name:
            parser.error("download requiere el nombre de archivo")
        download(base, token, args.name, args.out)
        return
    else:
        parser.error("Comando desconocido")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.command in ("photo", "audio"):
        download(base, token, result["name"], args.out)


if __name__ == "__main__":
    main()
