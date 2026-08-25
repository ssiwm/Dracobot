#!/usr/bin/env python3
"""E2E historii rozmów: session.list -> resume -> weryfikacja transkrypcji."""
import asyncio
import json
import re
import sys
import urllib.request

sys.path.insert(0, '/home/hermes/hermes-android-app/poc')
from poc_gateway import get_session_token, rpc


async def main():
    import websockets
    token = get_session_token()
    async with websockets.connect(f"ws://127.0.0.1:9119/api/ws?token={token}", max_size=None) as ws:
        # 1) lista sesji profilu koder (jak apka)
        await ws.send(rpc(1, "session.list", {"profile": "koder", "limit": 10}))
        while True:
            f = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
            if f.get("id") == 1:
                break
        sessions = (f.get("result") or {}).get("sessions", [])
        print(f"[list] sesji: {len(sessions)}")
        for s in sessions[:5]:
            print(f"   - {s['id']} | {s.get('title','')[:40]!r} | msgs={s.get('message_count')} | {s.get('preview','')[:40]!r}")
        if not sessions:
            print("[!] brak sesji do testu")
            return

        target = sessions[0]["id"]
        # 2) resume pierwszej
        await ws.send(rpc(2, "session.resume",
                          {"session_id": target, "profile": "koder", "cols": 80}))
        while True:
            f = json.loads(await asyncio.wait_for(ws.recv(), timeout=60))
            if f.get("id") == 2:
                break
        res = f.get("result") or {}
        if f.get("error"):
            print(f"[!] resume error: {f['error']}")
            return
        msgs = res.get("messages") or []
        print(f"[resume] ok sid={res.get('session_id')} messages={len(msgs)}")
        for m in msgs[:4]:
            if m.get("role") in ("user", "assistant"):
                print(f"   <{m['role']}> {m.get('text','')[:70]!r}")

asyncio.run(main())
