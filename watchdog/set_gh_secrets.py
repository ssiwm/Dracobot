#!/usr/bin/env python3
"""Ustawia sekrety GitHub Actions (KEYSTORE_BASE64, KEYSTORE_PROPERTIES,
GOOGLE_SERVICES_JSON) przez libsodium sealed box na publicznym kluczu repo."""
import base64
import json
import os
import sys
import urllib.request

from nacl import encoding, public

TOKEN = os.environ.get("GH_PAT", "")
OWNER, REPO = "ssiwm", "Dracobot"


def api(url, data=None, method=None):
    req = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "hermes-ci-setup",
        },
        data=json.dumps(data).encode() if data is not None else None,
    )
    resp = urllib.request.urlopen(req, timeout=30)
    body = resp.read()
    return json.loads(body) if body else {}


def put_secret(name: str, value: str) -> None:
    key_info = api(f"https://api.github.com/repos/{OWNER}/{REPO}/actions/secrets/public-key")
    pk = public.PublicKey(key_info["key"].encode(), encoding.Base64Encoder())
    sealed = public.SealedBox(pk).encrypt(value.encode())
    api(
        f"https://api.github.com/repos/{OWNER}/{REPO}/actions/secrets/{name}",
        data={"encrypted_value": base64.b64encode(sealed).decode(), "key_id": key_info["key_id"]},
        method="PUT",
    )
    print(f"[ok] secret {name} ustawiony")


if __name__ == "__main__":
    if not TOKEN:
        sys.exit("Ustaw GH_PAT")
    keystore_b64 = base64.b64encode(
        open("/home/hermes/hermes-android-app/keystore/hermesbots.keystore", "rb").read()
    ).decode()
    put_secret("KEYSTORE_BASE64", keystore_b64)
    put_secret(
        "KEYSTORE_PROPERTIES",
        open("/home/hermes/hermes-android-app/HermesBots/keystore.properties").read(),
    )
    put_secret(
        "GOOGLE_SERVICES_JSON",
        open("/home/hermes/hermes-android-app/HermesBots/app/google-services.json").read(),
    )
