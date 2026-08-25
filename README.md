# Hermes Bots (Dracobot) 🤖

Natywny klient Androida (Kotlin + Jetpack Compose, Material 3) dla **Bot Mode** z
[Hermes Agent](https://github.com/NousResearch/hermes-agent). Roster botów, czat ze
streamingiem, historia rozmów, routines i powiadomienia push FCM — wszystko przez
jedno połączenie WebSocket JSON-RPC do `hermes dashboard`.

![stack](https://img.shields.io/badge/Kotlin-2.0-7F52FF) ![compose](https://img.shields.io/badge/Compose%20M3-Expressive-4285F4) ![minSdk](https://img.shields.io/badge/minSdk-26-green)

## Funkcje

- **Roster botów** — awatary 1:1 z desktopowego Bot Mode (kształt + kolor deterministyczne z nazwy)
- **Czat 1:1** ze streamingiem odpowiedzi token-po-tokenie (`message.delta` / `message.complete`)
- **Historia rozmów** — `session.list` + `session.resume{profile}`; wybór rozmowy lub nowa
- **Routines** — lista cyklicznych zadań bota (`cron.manage`), pauza/wznowienie
- **Push FCM** — powiadomienie, gdy bot odpowie a apka jest w tle
- **Auto-connect / auto-reconnect** — health-check WS przy każdym `ON_RESUME`, kolejka offline
- **CrashGuard** — crash report na ekranie z przyciskiem kopiowania

## Architektura

```
UI (Compose ekrany)  →  AppViewModel (StateFlow)  →  GatewayClient (OkHttp WS, JSON-RPC 2.0)
                                                     ↘ AppStore (EncryptedSharedPreferences)
Serwer: hermes dashboard (:9119) ← Caddy (TLS+BasicAuth) ← Cloudflare Tunnel ← telefon
FCM bridge (Python, systemd): poll state.db → Firebase Cloud Messaging
```

Protokół gatewaya: `/api/ws?token=...` (token z SPA loopback), metody
`profiles.list`, `session.create{profile}`, `prompt.submit`, `session.resume`,
`cron.manage`. Szczegóły: `poc/poc_gateway.py` (działający PoC referencyjny).

## Budowanie

```bash
# wymagane: JDK 17, Android SDK 34; sdk.dir w local.properties (nie commitowany)
./gradlew assembleDebug          # debug
./gradlew assembleRelease        # release (wymaga keystore.properties — patrz niżej)
```

`keystore.properties` (nie commitowany):
```
storeFile=/sciezka/do/hermesbots.keystore
storePassword=...
keyAlias=hermesbots
keyPassword=...
```

### Release'y

Oficjalne, podpisane APK-i budowane są **lokalnie** (keystore nigdy nie opuszcza serwera
builda). CI na GitHub Actions buduje debug-APK przy każdym pushu do `main`
(artefakt w zakładce Actions) i służy weryfikacji kompilacji.

## Push FCM (opcjonalny)

1. Projekt Firebase + `app/google-services.json` (nie commitowany — wstaw własny)
2. Mostek po stronie serwera: [`bridge/fcm_bridge.py`](bridge/fcm_bridge.py)
   (systemd user unit; wymaga `firebase-admin`, klucz service-account wskazany env-em
   `HERMES_BOTS_FCM_CREDENTIALS`; endpoint `/register` przyjmuje tokeny urządzeń)
3. Apka rejestruje token automatycznie po starcie

## Struktura

| Katalog | Zawartość |
|---|---|
| `HermesBots/app/src/main/java/...` | apka: UI (Compose), `GatewayClient`, `AppViewModel`, FCM service |
| `bridge/fcm_bridge.py` | mostek push (gateway DB → FCM), rejestracja tokenów |
| `poc/` | skrypty PoC protokołu gatewaya (`poc_gateway.py`, `poc_history.py`, `poc_cron.py`) |
| `watchdog/watchdog.py` | health-check dashboard/caddy/cloudflared (cron-friendly: cisza = OK) |

## Bezpieczeństwo

- Hasła/logowania tylko w `EncryptedSharedPreferences` na urządzeniu
- `google-services.json`, `local.properties`, `keystore*`, `*.properties` z sekretami — **nigdy w repo**
- Serwer zakłada dostęp do dashboardu za reverse proxy z autoryzacją (np. BasicAuth/Tailscale)

## Licencja

MIT
