#!/usr/bin/env python3
"""FCM bridge: Hermes gateway -> Firebase Cloud Messaging -> telefon.

Dziala na serwerze (systemd user). Logika:
1. Polluje state.db kazdego profilu-bota (nowe wiersze w messages, role=assistant,
   zrodlo android-app / sesje kanoniczne Bot Chat) co POLL_SECONDS.
2. Nowa odpowiedz bota, ktorej nie widzielismy (apka w tle) -> push FCM:
   tytul = nazwa bota, tresc = poczatek odpowiedzi.
3. Wysyla tylko gdy apka NIE jest aktywnie polaczona WS do dashboardu
   (sprawdza liczbe polaczen przez ss; jak apka otwarta — push zbędny).
4. Tokeny FCM apka rejestruje POST-em /register; tokeny trzymane w tokens.json.

Uruchomienie:
  HERMES_BOTS_FCM_CREDENTIALS=/sciezka/service-account.json python3 fcm_bridge.py
Wymaga: pip install firebase-admin (venv: ~/hermes-android-app/bridge/venv)
"""
import json
import os
import sqlite3
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

# --- konfiguracja -----------------------------------------------------------
POLL_SECONDS = 4
PROFILES_DIR = "/home/hermes/.hermes/profiles"
DEFAULT_HOME = "/home/hermes/.hermes"
BRIDGE_PORT = 8765
TOKENS_FILE = os.path.expanduser("~/.hermes/caddy/fcm_tokens.json")
CRED_FILE = os.environ.get("HERMES_BOTS_FCM_CREDENTIALS",
                           "/home/hermes/.hermes/caddy/firebase-service-account.json")
MAX_PUSH_LEN = 180

BOTS = ["koder", "designer", "m-drala", "discord-bot"]  # default pomijamy (to ten agent)

_state_lock = Lock()
_seen = {}          # (bot, session_id) -> last message id wyslanego pusha
_tokens = set()


def load_tokens():
    if os.path.exists(TOKENS_FILE):
        with open(TOKENS_FILE) as f:
            _tokens.update(json.load(f))


def save_tokens():
    os.makedirs(os.path.dirname(TOKENS_FILE), exist_ok=True)
    with open(TOKENS_FILE, "w") as f:
        json.dump(sorted(_tokens), f)


def profile_db(bot: str):
    home = DEFAULT_HOME if bot == "default" else f"{PROFILES_DIR}/{bot}"
    path = os.path.join(home, "state.db")
    return path if os.path.exists(path) else None


def fetch_new_assistant_messages(bot: str):
    """Zwraca [(session_id, timestamp, content)] nowsze niz ostatnio widziane."""
    db_path = profile_db(bot)
    if not db_path:
        return []
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5)
    try:
        rows = con.execute(
            """SELECT m.session_id, m.id, m.timestamp, substr(m.content, 1, 300)
               FROM messages m JOIN sessions s ON s.id = m.session_id
               WHERE m.role = 'assistant'
                 AND (s.source = 'android-app' OR s.source = 'android-poc')
                 AND m.active = 1 AND m.observed = 0
               ORDER BY m.id ASC LIMIT 20""").fetchall()
    finally:
        con.close()
    out = []
    for sid, mid, ts, text in rows:
        key = (bot, sid)
        last = _seen.get(key, 0)
        if mid > last and text.strip():
            out.append((sid, mid, ts, text))
            _seen[key] = max(last, mid)
    return out


def app_is_open() -> bool:
    """Czy ktos ma otwarta apke (aktywne WS do dashboardu poza naszymi procesami)?"""
    try:
        out = subprocess_count_ws()
        return out > 1  # dashboard sam ma wewnetrzne polaczenia PTY-side
    except Exception:
        return False


def subprocess_count_ws() -> int:
    import subprocess
    r = subprocess.run(["ss", "-tn"], capture_output=True, text=True, timeout=10)
    return sum(1 for line in r.stdout.splitlines()
               if ":9119" in line and "ESTAB" in line and "127.0.0.1" not in line.split()[4].rsplit(":", 1)[0])


_fcm_app = None


def fcm_send(title: str, body: str) -> bool:
    global _fcm_app
    try:
        import firebase_admin
        from firebase_admin import credentials, messaging
        if _fcm_app is None:
            _fcm_app = firebase_admin.initialize_app(credentials.Certificate(CRED_FILE))
        if not _tokens:
            return False
        msg = messaging.MulticastMessage(
            notification=messaging.Notification(title=title, body=body[:MAX_PUSH_LEN]),
            tokens=sorted(_tokens),
        )
        resp = messaging.send_each_for_multicast(msg)
        # wyczysc martwe tokeny
        dead = [r for r in resp.responses if not r.success and "registration-token-not-registered" in str(r.exception)]
        if dead:
            pass  # uproszczenie: tokeny czysci apka przy przerejestrowaniu
        return any(r.success for r in resp.responses)
    except Exception as e:
        print(f"[fcm] blad wysylki: {e}", flush=True)
        return False


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/register":
            length = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(length) or b"{}")
            token = data.get("token", "")
            if token:
                with _state_lock:
                    _tokens.add(token)
                    save_tokens()
                self._json(200, {"ok": True})
            else:
                self._json(400, {"error": "token required"})
        else:
            self._json(404, {"error": "unknown"})

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # cisza w logach
        pass


def poll_loop():
    while True:
        try:
            if not app_is_open():
                for bot in BOTS:
                    for sid, mid, ts, text in fetch_new_assistant_messages(bot):
                        title = f"🤖 {bot}"
                        print(f"[push] {title}: {text[:60]!r}", flush=True)
                        fcm_send(title, text.strip())
                        time.sleep(0.3)  # rate limit FCM
        except Exception as e:
            print(f"[poll] blad: {e}", flush=True)
        time.sleep(POLL_SECONDS)


def main():
    load_tokens()
    # start: oznacz istniejace wiadomosci jako "widziane", zeby nie zalec starym pushem
    for bot in BOTS:
        try:
            db_path = profile_db(bot)
            if not db_path:
                continue
            con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5)
            rows = con.execute(
                """SELECT m.session_id, MAX(m.id) FROM messages m
                   JOIN sessions s ON s.id = m.session_id
                   WHERE m.role='assistant'
                     AND (s.source IN ('android-app','android-poc'))
                   GROUP BY m.session_id""").fetchall()
            con.close()
            for sid, mid in rows:
                _seen[(bot, sid)] = mid
        except Exception as e:
            print(f"[init] {bot}: {e}", flush=True)
    print(f"[bridge] start; tokenow: {len(_tokens)}; cred: {CRED_FILE}", flush=True)
    server = ThreadingHTTPServer(("127.0.0.1", BRIDGE_PORT), Handler)
    import threading
    threading.Thread(target=poll_loop, daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
