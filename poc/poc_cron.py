#!/usr/bin/env python3
"""Test cron.manage RPC (lista routines) na gatewayu."""
import asyncio, json, sys
sys.path.insert(0, '/home/hermes/hermes-android-app/poc')
from poc_gateway import get_session_token, rpc

async def main():
    import websockets
    token = get_session_token()
    async with websockets.connect(f"ws://127.0.0.1:9119/api/ws?token={token}", max_size=None) as ws:
        await ws.send(rpc(1, "cron.manage", {"action": "list", "include_disabled": True, "profile": "koder"}))
        while True:
            f = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
            if f.get("id") == 1:
                break
        res = f.get("result") or {}
        jobs = res.get("jobs") or []
        print(f"[cron] jobs={len(jobs)}")
        for j in jobs[:5]:
            print(f"  - id={j.get('job_id')} name={j.get('name','')[:40]!r} sched={j.get('schedule')} enabled={j.get('enabled')}")
asyncio.run(main())
