# Hermes Bots — aplikacja Android (plan frontendu)

Cel: natywny klient Androida dla **Bot Mode** z Hermes gateway. Frontend wzorowany na
apce **Grok** (czysty, ciemny, czat-first, karty botów) w stylistyce **Material 3 Expressive**
(najnowszy Android 16 design language).

---

## 0. Status realizacji (2026-08-24)

| Krok | Status |
|---|---|
| PoC protokołu gateway (WS JSON-RPC, auth, roster, streaming czatu) | ✅ `poc/poc_gateway.py` |
| Szkielet apki Compose + build APK na serwerze (JDK17+SDK34+Gradle w ~/tools, bez roota) | ✅ v0.1.0 |
| Proxy Caddy: TLS :9443 + Basic Auth → dashboard 127.0.0.1:9119; publicznie `https://151.115.90.197:9443`, user `maccv`; unitka systemd user `caddy-hermes.service` | ✅ |
| Apka v0.2.0: ekran Connect z loginem proxy, trustAll SSL (self-signed), dostęp z internetu zweryfikowany (401 bez hasła / WS przez proxy OK) | ✅ |
| **Stały adres: `https://bots.draconest.eu`** — nazwany tunel Cloudflare (systemd user `cloudflared-hermes.service`) + Caddy BasicAuth; obejście filtra botów CF (własny User-Agent `HermesBots/0.3` w OkHttp) | ✅ v0.3.0 |
| Naprawiony crash czatu (duplikaty kluczy LazyColumn) + CrashGuard z ekranem crash-reportu | ✅ v0.2.1+ |
| Do zrobienia później: UI polish (grupy botów, Routines, blob avatary 1:1 z desktopu), release signing, historia rozmów (session.resume) | ⏳ |

Instalacja na telefonie: zainstaluj APK (zezwól na nieznane źródła) → URL `https://bots.draconest.eu` (predefiniowany) → user `maccv` → hasło z `~/.hermes/caddy/credentials.txt`.


## 0. PoC — ZWERYFIKOWANE 2026-08-24 ✅
`poc/poc_gateway.py` (działa, venv Hermesa): auth tokenem ze SPA (`window.__HERMES_SESSION_TOKEN__`),
WS `/api/ws?token=...`, `profiles.list` → roster 5 botów, `session.create{profile}` → sesja na profilu,
`prompt.submit` → stream `message.delta`/`message.complete`. **Fundamenty potwierdzone.**
Pełna dokumentacja protokołu: skill `hermes-gateway-client`.

## 1. Stack

| Warstwa | Wybór | Dlaczego |
|---|---|---|
| Język / UI | **Kotlin + Jetpack Compose + Material 3 Expressive** | natywny, najnowszy styl Androida |
| Min SDK | 26 (Android 8), target 36 (Android 16) | zasięg vs. nowe API |
| Sieć | OkHttp + **WebSocket (JSON-RPC)** do `hermes gateway` | ten sam protokół co desktop TUI |
| Serializacja | kotlinx.serialization | lekkie, natywne |
| DI | Hilt | standard |
| Push | FCM + mini-mostek na serwerze | gateway nie ma push |
| Persystencja | Room (cache historii czatów, roster offline) | offline-first UX |
| Async | Coroutines + Flow | streaming odpowiedzi bota |

## 2. Architektura (MVVM, 3 warstwy)

```
UI (Compose ekrany)
   ↓ ViewModel (StateFlow)
Repository (BotsRepository, ChatRepository, RoutinesRepository)
   ↓
GatewayClient (WS JSON-RPC: session.create/resume, prompt.submit{profile}, profiles.*, cron.*)
   + PushBridge (FCM) + Room cache
```

Serwer: istniejący `hermes gateway` (bez modyfikacji) + 2 dodatki:
- reverse proxy z tokenem auth (np. Caddy/nginx) — dostęp z internetu
- `push-bridge`: mały serwis (Python/Node), słucha WS gatewaya → wysyła do FCM

## 3. Ekrany (wzorowane na Grok)

### 3.1 Ekran główny — Roster botów (odpowiednik "Grok" home)
- Górny pasek: duży tytuł **„Boty”** (Material 3 expressive typography), avatar użytkownika po prawej
- Pasek wyszukiwania (pill, jak w Grok)
- **Active now** — poziomy rządek chipów botów pracujących w tej chwili (pulsująca kropka)
- Lista botów: **avatar (blob face — ten sam algorytm co desktop!)**, nazwa, tytuł,
  podgląd ostatniej wiadomości, timestamp, badge nieprzeczytanych
- FAB „+” → New Bot (bottom sheet: Name / Title / Description → bot żyje w sekundę)
- Nawigacja: **NavigationBar** (3 zakładki): Boty · Grupy · Routines

### 3.2 Czat z botem
- Nagłówek: avatar bota + nazwa + status („pisze…” z animowanymi kropkami)
- Wiadomości: dymki — użytkownik (wypełniony, primary color), bot (powierzchniowy, bez avatara przy kolejnych wiadomościach — grupowanie jak w Grok)
- **Streaming odpowiedzi token-po-tokenie** (Flow z WS)
- Composer: zaokrąglone pole + przycisk wyślij (morphing: send → stop podczas generowania)
- Markdown + code blocks z przyciskiem kopiowania
- Long-press wiadomości: kopiuj / regeneruj / @wspomnij innego bota

### 3.3 Czat grupowy (2–6 botów)
- Jak czat, ale każda wiadomość bota z avatarem i kolorem akcentu bota
- Badge **„potrzebuje ciebie”** (@user) na liście grup
- Wskaźnik „runda 2/3” gdy boty deliberują

### 3.4 Routines
- Lista kart: nazwa, bot-właściciel (avatar), harmonogram („codzień 9:00”), status toggle
- Ekran edycji: picker harmonogramu (częstotliwość → szczegóły, jak w desktopie) + pole Advanced (raw schedule string)
- Historia ostatnich uruchomień w czacie bota

### 3.5 Tworzenie/edycja bota
- Bottom sheet → pełny ekran: Name, Title, Description
- Sekcja Advanced: model pin (dropdown provider/model), SOUL.md (edytor), przełączniki skills/toolsets/MCP
- Podgląd **blob avatara na żywo** podczas wpisywania nazwy + przycisk „Losuj” (jak desktop)

### 3.6 Ustawienia
- Połączenie: URL gatewaya + token (logowanie), tryb Tailscale/VPN info
- Motyw: dark (domyślny, jak Grok) / light / dynamic color (Material You)
- Powiadomienia per bot

## 4. Design system — Material 3 Expressive

- **Dynamic color (Material You)**: paleta z tapety użytkownika; fallback: ciemny grafit + akcent (jak Grok: czerń #0A0A0A, szarości, jeden akcent)
- **Kształty**: duże promienie (28dp karty, 24dp dymki, pill przyciski); expressive shape morphing na FAB i przycisku wysyłania
- **Typografia**: variable font (Google Sans Flex / Roboto Flex), duże tytuły ekranów
- **Motion**: physics-based (spring), shared-element przejście roster → czat (avatar leci w nagłówek)
- **Komponenty expressive**: ButtonGroup, LoadingIndicator (nowy M3 Expressive spinner), SplitButton
- Ciemny motyw **default** (czat-first apka, jak Grok)

## 5. Kamienie milowe

| # | Zakres | Czas |
|---|---|---|
| M0 | Projekt: GatewayClient (WS/JSON-RPC) + auth proxy — testy z gatewayem | 1 tyg. |
| M1 | Roster + czat 1:1 ze streamingiem (MVP!) | 2 tyg. |
| M2 | Grupy, Routines (read + toggle) | 1,5 tyg. |
| M3 | Tworzenie/edycja botów, avatary blob, push FCM | 2 tyg. |
| M4 | Polish: motion, dynamic color, offline cache, publikacja (Play internal) | 1,5 tyg. |

**Razem: ~8 tygodni** dla 1 dev + agent AI. MVP (M0+M1) używalne po ~3 tygodniach.

## 6. Ryzyka
- Auth z internetu → rozwiązane proxy + token (lub Tailscale dla prywatnego użytku)
- Push wymaga własnego mostku → najprostszy wariant: poll przy otwarciu apki + push tylko dla @mentions
- Blob avatar: algorytm deterministyczny z nazwy — trzeba zreplikować z kodu desktopowej wtyczki (open source: NousResearch/Hermes-Bot-Mode)
