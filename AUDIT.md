# Audyt ObserverWidget – Bateria, Powiadomienia PUSH, Alerty offline

**Data:** 2026-11-14  
**Wersja bazowa:** 2.10.5 (commit na `main`)  
**Gałąź:** `audit/battery-optimization-v3`

---

## Znalezione problemy

### 🔴 KRYTYCZNE – Bateria

| # | Problem | Plik | Skutek |
|---|---------|------|--------|
| 1 | Permanentny `PARTIAL_WAKE_LOCK` odnawiany co obrót pętli (10 min TTL) | `MonitorService.kt:99-115` | CPU nigdy nie wchodzi w Deep Sleep – nawet w nocy |
| 2 | Pętla 1 s tyka i odpytuje sieć także przy wyłączonym ekranie | `MonitorService.kt:75-91` | Radio LTE/5G nigdy nie przechodzi w RRC IDLE (tail time) |
| 3 | `HttpURLConnection.disconnect()` po każdym zapytaniu | `ObserverApi.kt:64`, `MqttStatusApi.kt:51`, `MapperApi.kt:95` | Brak puli połączeń → 6–7 nowych handshake TLS co 15 s |
| 4 | `RemoteViews` + Binder IPC co 1 s do Launchera | `ObserverRefresh.kt:213-219`, `MonitorService.kt:87` | Ciągła praca GPU/CPU w procesie systemowym |
| 5 | `MainActivity`: `removeAllViews()` co 1 s i budowanie UI od nowa | `MainActivity.kt:237` | GC churn, relayout, alokacje |

### 🟡 WAŻNE – Powiadomienia PUSH (opóźnienie ~40 min)

| # | Problem | Plik | Mechanizm |
|---|---------|------|-----------|
| 6 | **Brak trwałości stanu alertów (`alert_state`)** — po restarcie procesu `KeyState` zaczyna się od `bad=false` | `Alerts.kt:219-247` | Android ubija usługę w tle → stan alertów się resetuje → automat czeka na nowe przejście GOOD→BAD, a `notified=false` dopiero po powrocie do GOOD |
| 7 | **`firstRun` mapera tłumi powiadomienia** — po restarcie procesu `MapperMonitor.loaded=false`, kursor zostaje, ale `firstRun = config.mapperLastId <= 0` jest `false`, więc pingi są świeże – ALE: `ensureLoaded()` ładuje stan z dysku, a `poll()` zwraca `fresh` = puste przy `firstRun` | `MapperMonitor.kt:196-247` | Pierwsze odpytanie po restarcie traktuje zastane dane jako „historię" |
| 8 | **Stan `alert_state` trwały, ale klucz `map_last` nie jest resetowany po restarcie procesu** – skutek: `newSession = nowMs - lastAt > SESSION_GAP_MS` → jeśli proces leżał >5 min, nowy mapper i tak wchodzi jako „nowa sesja" | `Alerts.kt:84` | Efekt netto: po restarcie (który zdarza się przez Doze/Android agresywne zarządzanie pamięcią) mija 15-min cykl WorkManagera zanim w ogóle odpytanie się wykona |

**Scenariusz opóźnienia 40 min:**  
Android ubija `MonitorService` (Doze, oszczędzanie baterii OEM) → wakelock zwolniony → zostaje tylko `RefreshWorker` co 15 min (minimum WM) → WorkManager budzi się, `refreshOnce()` → `evaluateAlerts()` → ale `KeyState` w `alert_state` nadal ma `notified=true` z poprzedniego epizodu (trwałe prefs!) → alarm nie idzie → dopiero jak dane się poprawią (GOOD) i znowu pogorszą (BAD), automat przejdzie pełny cykl → wtedy push, ale z opóźnieniem WM (15 min) × parę cykli.

### 🟡 WAŻNE – Alarmy a brak internetu

| # | Problem | Plik |
|---|---------|------|
| 9 | Gdy telefon jest offline, `MqttStatusApi` zwraca `Probe(Health.BAD, networkError=true)` | `MqttStatusApi.kt:53-54` |
| 10 | W `ObserverRefresh.collect()` logika `phoneOffline` wykrywa to i zmienia na `UNKNOWN` | `ObserverRefresh.kt:379-384` |
| 11 | **ALE**: `ObserverApi.fetch()` też pada → `broker1/broker2 = FetchResult.Fail` → `data.observer == null` | `Alerts.kt:51-53` |
| 12 | Warunek `bothApisDown` = `true` → `observerHealth = Health.BAD` **mimo że telefon jest offline** | `Alerts.kt:53` |
| 13 | **Skutek:** Użytkownik dostaje fałszywy alarm „Observer nie wysyła pakietów" gdy wejdzie w tunel / straci zasięg | — |

---

## Wdrożone poprawki

### Krok 1: MonitorService – adaptacyjna pętla + krótkotrwały wakelock
- Wakelock pobierany TYLKO na czas `refreshAll` (max 30 s), nie permanentny
- Przy wyłączonym ekranie pętla usypia na pełny `intervalSec` (nie tyka co 1 s)
- Rejestracja `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` do natychmiastowego wznowienia/wstrzymania tykania

### Krok 2: Chronometer w RemoteViews zamiast IPC co 1 s
- Widget używa `Chronometer` (natywne odliczanie w Launcherze) zamiast aktualizacji tekstu co sekundę
- `renderAll()` wywoływane tylko przy zmianie danych (co `intervalSec`), nie co 1 s

### Krok 3: Usunięcie `connection.disconnect()` – pula HTTP
- `disconnect()` zastąpione zamknięciem strumieni (`inputStream.close()`)
- Umożliwia `Keep-Alive` i reuse socketów TCP/TLS

### Krok 4: Fix powiadomień PUSH – natychmiastowość
- Rozróżnienie „telefon offline" od „Observer/broker padł" na poziomie `Alerts.evaluate()`
- `phoneOffline` → **żadne alarmy nie idą** (brak internetu to nie awaria sieci mesh)
- Po odzyskaniu internetu: natychmiastowa ewaluacja z bieżącym stanem
- `RefreshWorker` wzmocniony: wykrywa, że usługa nie działa → próbuje ją restartować

### Krok 5: Fix renderowania UI w MainActivity
- Zamiast `removeAllViews()` co 1 s → aktualizacja tekstów istniejących widoków
- Ticker w Activity aktualizuje tylko licznik sekund, nie przebudowuje drzewa
