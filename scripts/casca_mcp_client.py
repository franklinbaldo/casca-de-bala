#!/usr/bin/env python3
"""Casca de Bala MCP client for a trusted Tailscale/loopback host.

Stdlib only. Never prints bearer tokens. Not a public OAuth MCP gateway.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib import error, parse, request

TAILSCALE_V4 = ipaddress.ip_network("100.64.0.0/10")
TAILSCALE_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")
PAIR_TOKEN = re.compile(r"^CASCA_TOKEN=(casca_[A-Za-z0-9_-]{43})$")


class CascaError(Exception):
    pass


def checked_url(raw: str) -> str:
    parsed = parse.urlsplit(raw)
    if (parsed.scheme != "http" or parsed.username is not None
            or parsed.password is not None or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise CascaError("Use apenas http://IP:PORTA (sem credenciais ou caminhos).")
    try:
        host = ipaddress.ip_address(parsed.hostname or "")
        port = parsed.port
    except ValueError as exc:
        raise CascaError("O endereço deve usar IP numérico e porta válida.") from exc
    if not port or not (host.is_loopback or host in TAILSCALE_V4 or host in TAILSCALE_V6):
        raise CascaError("Por segurança, apenas IP de loopback ou Tailscale.")
    return raw.rstrip("/")


def state_path() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")).expanduser()
    return base / "casca-de-bala" / "jatoba.json"


def save_token(url: str, token: str) -> None:
    if not PAIR_TOKEN.fullmatch("CASCA_TOKEN=" + token):
        raise CascaError("Credencial do Casca inválida.")
    destination = state_path()
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(destination.parent, 0o700)
    fd, tmp = tempfile.mkstemp(dir=destination.parent, prefix=".jatoba-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump({"url": url, "token": token}, output)
            output.write("\n")
        os.chmod(tmp, 0o600)
        os.replace(tmp, destination)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def load_token(url: str) -> str:
    path = state_path()
    if path.is_symlink() or (path.stat().st_mode & 0o077):
        raise CascaError("Arquivo de credenciais inseguro (exige modo 0600).")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("url") != url or not isinstance(data.get("token"), str):
        raise CascaError("Credencial salva para outro IP; pareie novamente.")
    token = data["token"]
    if not PAIR_TOKEN.fullmatch("CASCA_TOKEN=" + token):
        raise CascaError("Credencial salva inválida.")
    return token


def imported_token(body: str) -> str:
    for line in body.splitlines():
        match = PAIR_TOKEN.fullmatch(line.strip())
        if match:
            return match.group(1)
    raise CascaError("Resposta não contém aprovação e CASCA_TOKEN válido.")


def pair(url: str, client: str) -> None:
    if not 1 <= len(client) <= 64 or any(ord(c) < 32 for c in client):
        raise CascaError("Nome do cliente inválido.")
    body = json.dumps({"client": client}).encode()
    req = request.Request(url + "/pair", data=body, headers={"Content-Type": "application/json"})
    with request.urlopen(req, timeout=135) as response:
        print(response.readline().decode("utf-8", errors="replace").strip(), flush=True)
        print("Confirme a notificação no celular. O token não será exibido.", flush=True)
        # Read line-by-line because /pair streams a code before the approval.
        lines = []
        for line in response:
            text = line.decode("utf-8", errors="replace").strip()
            if text.startswith("CASCA_TOKEN="):
                lines.append(text)
            elif text:
                print(text, flush=True)
        token = imported_token("\n".join(lines))
    save_token(url, token)
    print("Pareamento concluído. Credencial salva com permissão 0600.")


def rpc(url: str, token: str, method: str, ident: int, params: dict | None = None) -> dict:
    message = {"jsonrpc": "2.0", "id": ident, "method": method}
    if params is not None:
        message["params"] = params
    req = request.Request(
        url + "/mcp", data=json.dumps(message).encode(),
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    with request.urlopen(req, timeout=15) as response:
        answer = json.load(response)
    if answer.get("id") != ident or answer.get("jsonrpc") != "2.0":
        raise CascaError("Resposta JSON-RPC inválida.")
    if "error" in answer:
        raise CascaError("Erro JSON-RPC: " + str(answer["error"].get("message", "desconhecido")))
    if "result" not in answer:
        raise CascaError("Resposta MCP sem result.")
    return answer["result"]


def inspect(url: str, command: str) -> None:
    token = load_token(url)
    init = rpc(url, token, "initialize", 1, {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "jatoba-casca", "version": "0.1"},
    })
    if command == "doctor":
        tools = rpc(url, token, "tools/list", 2).get("tools", [])
        print(json.dumps({
            "connected": True, "server": init.get("serverInfo", {}),
            "protocolVersion": init.get("protocolVersion"),
            "tools": [item["name"] for item in tools],
        }, ensure_ascii=False, indent=2))
        return
    if command == "tools":
        print(json.dumps(rpc(url, token, "tools/list", 2), ensure_ascii=False, indent=2))
        return
    name = "casca_capabilities" if command == "capabilities" else "band_snapshot"
    result = rpc(url, token, "tools/call", 2, {"name": name, "arguments": {}})
    print(json.dumps(result, ensure_ascii=False, indent=2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="http://IP-TAILSCALE:8765")
    sub = parser.add_subparsers(dest="command", required=True)
    pairing = sub.add_parser("pair", help="Solicita aprovação no celular e salva token privado")
    pairing.add_argument("--client", default="ChatGPT via Jatoba")
    importing = sub.add_parser("import-pair", help="Importa resposta local de /pair, sem mostrar token")
    importing.add_argument("file", type=Path, help="Arquivo privado contendo CASCA_TOKEN")
    for name in ("doctor", "tools", "capabilities", "snapshot"):
        sub.add_parser(name)
    args = parser.parse_args(argv)
    try:
        url = checked_url(args.url)
        if args.command == "pair":
            pair(url, args.client)
        elif args.command == "import-pair":
            src = args.file
            if src.is_symlink() or (src.stat().st_mode & 0o077):
                raise CascaError("Arquivo de importação deve ter permissão 0600.")
            save_token(url, imported_token(src.read_text(encoding="utf-8")))
            print("Credencial importada de arquivo privado (0600).")
        else:
            inspect(url, args.command)
        return 0
    except FileNotFoundError:
        print("Erro: credencial ausente. Use pair ou import-pair.", file=sys.stderr)
    except error.HTTPError as exc:
        print("Erro HTTP: " + str(exc.code) + (" (pareamento ausente/revogado)" if exc.code == 401 else ""), file=sys.stderr)
    except (error.URLError, TimeoutError, ConnectionError):
        print("Erro de rede: celular indisponível; confira tailnet e servidor MCP.", file=sys.stderr)
    except (CascaError, json.JSONDecodeError, UnicodeError, OSError) as exc:
        print("Erro: " + str(exc), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
