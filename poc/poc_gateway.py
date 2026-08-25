#!/usr/bin/env python3
"""PoC: klient Android <-> Hermes gateway (dashboard WS JSON-RPC).

Cykl testowy:
1. Pobiera token sesji ze SPA (loopback mode).
2. WS -> /api/ws?token=...
3. profiles.list  - roster botow (Bot Mode)
4. session.create {profile} - nowa sesja na wybranym profilu
5. prompt.submit {session_id, text} - prompt + stream odpowiedzi
"""
import asyncio
import json
import re
import sys
import urllib.request

BASE = "http://127.0.0.1:9119"
WS_URL = "ws://127.0.0.1:9119/api/ws"
PROFILE = sys.argv[1] if len(sys.argv) > 1 else "designer"
PROMPT = ("Odpowiedz DOKLADNIE jednym krotkim zdaniem po polsku: potwierdz polaczenie "
          "testowego klienta mobilnego z gatewayem Hermes. Nie uzywaj zadnych narzedzi.")
MAX_WAIT_S = 120


def get_session_token() -> str:
    html = urllib.request.urlopen(BASE + "/", timeout=5).read().decode()
    m = re.search(r'window\.__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"', html)
    if not m:
        raise RuntimeError("Brak tokenu w SPA HTML (gated mode?)")
    return m.group(1)


def rpc(rid: int, method: str, params: dict | None = None) -> str:
    return json.dumps({"jsonrpc": "2.0", "id": rid, "method": method,
                       "params": params or {}})


async def main() -> None:
    import websockets

    token = get_session_token()
    print(f"[auth] token OK ({len(token)} znakow)")

    async with websockets.connect(f"{WS_URL}?token={token}", max_size=None) as ws:
        # -- 1) Roster profili --------------------------------------------
        await ws.send(rpc(1, "profiles.list"))
        while True:
            frame = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
            if frame.get("id") == 1:
                break
        profs = frame.get("result", {}).get("profiles", [])
        print(f"\n=== ROSTER ({len(profs)} botow) ===")
        for p in profs:
            gw = "gateway ON" if p.get("gateway_running") else ""
            print(f"  - {p['name']:<12} model={p.get('model')} skills={p.get('skill_count')} {gw}")

        # -- 2) Nowa sesja na profilu bota ---------------------------------
        await ws.send(rpc(2, "session.create",
                          {"profile": PROFILE, "title": "PoC apka Android",
                           "source": "android-poc"}))
        sid = None
        while True:
            frame = json.loads(await asyncio.wait_for(ws.recv(), timeout=60))
            if frame.get("id") == 2:
                res = frame.get("result") or {}
                sid = res.get("session_id") or res.get("sid")
                err = frame.get("error")
                if err or not sid:
                    print(f"[!] session.create error: {err or res}")
                    return
                break
        print(f"\n[session] id={sid} profile={PROFILE}")

        # -- 3) Prompt + stream -------------------------------------------
        await ws.send(rpc(3, "prompt.submit", {"session_id": sid, "text": PROMPT}))
        print("[stream] ---")
        answer: list[str] = []
        deadline = asyncio.get_event_loop().time() + MAX_WAIT_S
        got_submit_ack = False
        while asyncio.get_event_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=max(1, deadline - asyncio.get_event_loop().time()))
            except asyncio.TimeoutError:
                break
            frame = json.loads(raw)
            m = frame.get("method", "")
            p = frame.get("params") or {}
            if frame.get("id") == 3:
                got_submit_ack = True
                if frame.get("error"):
                    print(f"[!] prompt.submit error: {frame['error']}")
                    return
                continue
            # podglad strumienia: text delty przychodza jako
            # {"method":"event","params":{"type":"message.delta","payload":{"text":...}}}
            m = frame.get("method", "")
            etype = p.get("type", "") if isinstance(p, dict) else ""
            payload = p.get("payload") or {} if isinstance(p, dict) else {}
            if etype == "message.delta" and isinstance(payload.get("text"), str):
                answer.append(payload["text"])
                print(f"  <delta> {payload['text']!r}")
            elif etype == "message.complete":
                print(f"  <complete> {(payload.get('text') or '')[:120]!r}")
                break
            elif etype in ("thinking.delta", "reasoning.delta"):
                pass  # pomijamy tokenu myslenia
            elif etype:
                print(f"  <{etype}>")

        full = "".join(answer).strip()
        print("\n=== WYNIK ===")
        print(full[:500] if full else "(brak zebraego tekstu - sprawdz log metod powyzej)")


if __name__ == "__main__":
    asyncio.run(main())
