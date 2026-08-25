#!/usr/bin/env python3
"""Watchdog uslug Hermes Bots: dashboard, caddy, cloudflared.

Sprawdza:
1. systemd user units (caddy-hermes, cloudflared-hermes) — czy active
2. dashboard — czy proces hermes dashboard zyje i czy / odpowiada lokalnie
3. publiczny lanuchuch https://bots.draconest.eu (przez Cloudflare) — czy 401/200

Wyjscie:
- pusto  -> wszystko OK (watchdog pattern: brak wiadomosci = spokoj)
- tekst  -> raport awarii do dostarczenia na Discord
"""
import subprocess
import sys
import urllib.request
import base64

DOMAIN = "https://bots.draconest.eu"
CREDS = "/home/hermes/.hermes/caddy/credentials.txt"
XDG = {"XDG_RUNTIME_DIR": f"/run/user/{__import__('os').getuid()}"}


def unit_active(name: str) -> bool:
    r = subprocess.run(
        ["systemctl", "--user", "is-active", name],
        capture_output=True, text=True, env=XDG, timeout=10)
    return r.stdout.strip() == "active"


def dashboard_ok() -> tuple[bool, str]:
    # proces
    r = subprocess.run(["pgrep", "-f", "hermes_cli.main dashboard"], capture_output=True, timeout=10)
    if r.returncode != 0:
        return False, "proces dashboard nie zyje"
    # lokalny HTTP + token w SPA
    try:
        html = urllib.request.urlopen("http://127.0.0.1:9119/", timeout=8).read().decode()
        if "__HERMES_SESSION_TOKEN__" not in html:
            return False, "dashboard odpowiada, ale SPA bez tokenu"
    except Exception as e:
        return False, f"lokalny HTTP padl: {e}"
    return True, ""


def public_ok() -> tuple[bool, str]:
    try:
        user, passwd = open(CREDS).read().strip().split("PASS=")[0].split("USER=")[1].split()[0], \
                       open(CREDS).read().strip().split("PASS=")[1].split()[0]
        auth = base64.b64encode(f"{user}:{passwd}".encode()).decode()
        req = urllib.request.Request(DOMAIN + "/", headers={"Authorization": f"Basic {auth}",
                                                            "User-Agent": "HermesBots-watchdog/1.0"})
        resp = urllib.request.urlopen(req, timeout=15)
        if resp.status == 200 and "__HERMES_SESSION_TOKEN__" in resp.read().decode():
            return True, ""
        return False, f"publiczny HTTP status={resp.status} bez tokenu SPA"
    except urllib.error.HTTPError as e:
        return False, f"publiczny HTTP {e.code} ({DOMAIN})"
    except Exception as e:
        return False, f"publiczny HTTP padl: {type(e).__name__}: {e}"


def main() -> int:
    problems = []

    if not unit_active("caddy-hermes.service"):
        problems.append("❌ caddy-hermes.service nieaktywny (proxy TLS na :9443)")
    if not unit_active("cloudflared-hermes.service"):
        problems.append("❌ cloudflared-hermes.service nieaktywny (tunel bots.draconest.eu)")

    ok, why = dashboard_ok()
    if not ok:
        problems.append(f"❌ dashboard Hermes: {why}")

    ok, why = public_ok()
    if not ok:
        problems.append(f"❌ dostęp publiczny: {why}")

    if not problems:
        print("")  # cisza = zdrowie
    else:
        print("🚨 **Hermes Bots watchdog** — apka na telefonie może nie działać:")
        print("\n".join(f"- {p}" for p in problems))
        print("\nPodpowiedź: `systemctl --user restart caddy-hermes cloudflared-hermes` "
              "albo zapytaj mnie o naprawę.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
