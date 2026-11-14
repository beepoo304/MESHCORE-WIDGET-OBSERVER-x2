# Podsumowanie Zmian, Poprawek i Analiza Architektoniczna (v2.10.6)

**Gałąź:** `audit/battery-optimization-v3`  
**Wersja:** 2.10.6 (versionCode 26)  
**Data:** 2026-11-14  

---

## 1. Wykonane prace i wdrożone poprawki

### A. Optymalizacja zużycia baterii
1. **Wyeliminowano permanentny `PARTIAL_WAKE_LOCK` (24/7):**
   * Poprzednio: `MonitorService.renewWakeLock()` podtrzymywał blokadę procesora bez przerwy w nieskończoność (10-minutowy wakelock odnawiany co sekundę). Telefon w nocy ani na chwilę nie wchodził w stan głębokiego uśpienia (*Deep Sleep*).
   * Teraz: Całkowicie usunięto stały wakelock z `MonitorService`. Krótkotrwały wakelock (`PARTIAL_WAKE_LOCK`) pobierany jest **wyłącznie wewnątrz `ObserverRefresh.cycle`** na czas trwania zapytania sieciowego (maks. 15 s, w praktyce 1–2 s) i natychmiast zwalniany w bloku `finally`. W kieszeni procesor śpi.

2. **Warunkowe pomijanie rysowania widgetu przy wyłączonym ekranie:**
   * Poprzednio: Usługa budziła proces systemowy Androida (`system_server`) oraz proces Launchera transakcjami Binder IPC (`updateAppWidget`) co 1 sekundę przez całą dobę.
   * Teraz: Sprawdzany jest stan `power.isInteractive != false`. Gdy ekran jest zgaszony (telefon w kieszeni lub leży zablokowany), `renderAll` jest całkowicie pomijane. Pobieranie danych działa w tle, ale nie obciąża GPU ani IPC.

3. **Poprawka płynności po wybudzeniu ekranu (brak zamrożenia):**
   * Problem w wersji testowej: Zastosowanie długiego uśpienia (`delay(sleepMs)`) przy wyłączonym ekranie powodowało, że po włączeniu ekranu pętla wciąż spała przez resztę 15-sekundowego interwału, dając efekt „zamrożonych liczników”.
   * Rozwiązanie: Pętla w `MonitorService` tyka równomiernie co 1 sekundę na granicy pełnej sekundy zegara. Dzięki temu po odblokowaniu telefonu widget ożywa **natychmiast (w ułamku sekundy)**, bez żadnego opóźnienia.

4. **Stabilne gniazda sieciowe (`HttpURLConnection`):**
   * Wycofano eksperyment z usuwaniem `c.disconnect()`. W Androidzie standardowy klient `HttpURLConnection` przy braku `disconnect()` potrafi zawieszać gniazda w puli przy błędach 4xx/5xx lub braku pełnego odczytu strumienia błędu. Pozostawiono pewne zamykanie połączeń `disconnect()`, eliminując zacięcia sieciowe.

5. **Pamięć podręczna (*cache*) bitmapy strzałki mapera:**
   * W `WidgetRenderer.mappingArrowBitmap` wprowadzono buforowanie wygenerowanej bitmapy. Jeśli azymut, odległość i kolor nie uległy zmianie, funkcja zwraca istniejącą bitmapę zamiast alokować nowy obiekt `Bitmap.createBitmap()` co 1 sekundę.

---

### B. Poprawa logiki powiadomień PUSH i obsługi offline

1. **Rozwiązanie problemu opóźnionych powiadomień (~40 minut):**
   * **Przyczyna:** Gdy telefon budził się po dłuższym uśpieniu (np. w nocy lub po wyjściu z trybu Doze), funkcja `MapperMonitor.poll` pobierała zaległe punkty z bazy mapera. Funkcja `Alerts.evaluateMapping` traktowała te punkty jako „nowe” (`fresh`) i wysyłała alert PUSH o mapowaniu, które w rzeczywistości zakończyło się 40 minut wcześniej.
   * **Poprawka:** W `Alerts.evaluateMapping` oraz `Alerts.evaluateRptMapping` dodano warunek weryfikujący znacznik czasu najnowszego punktu:
     ```kotlin
     val newestPing = fresh.maxByOrNull { it.tsMs } ?: return
     if (nowMs - newestPing.tsMs > MapperApi.ACTIVE_MS) return
     ```
     Jeśli najnowszy ping jest starszy niż próg aktywnego mapowania (3 minuty), powiadomienie PUSH jest blokowane jako przeterminowane.

2. **Brak internetu w telefonie a fałszywe alarmy:**
   * **Przyczyna:** Gdy telefon tracił zasięg (np. winda, tunel, tryb samolotowy), wszystkie zapytania HTTP kończyły się błędem. Logika alarmów traktowała brak odpowiedzi serwerów jako awarię Observera i wysyłała fałszywy alert: *„Observer nie wysyła pakietów / brak odpowiedzi API”*.
   * **Poprawka:** Dodano flagę `phoneOffline` w `WidgetData`. Jeśli wszystkie źródła zgłaszają błąd sieciowy telefonu:
     * Stan alarmów przyjmuje wartość `Health.UNKNOWN` (wstrzymanie, brak fałszywego PUSH).
     * Rejestr przestojów (*Outages*) nie otwiera nowego incydentu awarii dla Observera.

---

## 2. Dlaczego komponent `android.widget.Chronometer` NIE NADAJE SIĘ do tego widgetu?

Podczas audytu analizowano zastąpienie 1-sekundowych aktualizacji widgetu natywnym komponentem `android.widget.Chronometer` dostępnym w `RemoteViews`. 

W teorii `Chronometer` pozwala Launcherowi samodzielnie odliczać czas na ekranie. Jednak w architekturze **ObserverWidget** jego użycie jest technicznie niemożliwe bez drastycznego okrojenia funkcjonalności.

### Kluczowe ograniczenia techniczne `Chronometer` w `RemoteViews`:

1. **Brak obsługi progów kolorystycznych (krytyczne dla Observera):**
   * Wiek ostatniego pakietu musi dynamicznie zmieniać kolor:
     * `< 3 min` → **zielony** (LIVE)
     * `3–5 min` → **pomarańczowy** (IDLE)
     * `> 5 min` → **czerwony** (NO PKT / ERROR)
   * `Chronometer` w Androidzie posiada **wyłącznie statyczny kolor tekstu**. Komponent działający po stronie Launchera nie ma żadnej logiki biznesowej, nie potrafi badać upływu minut ani zmieniać własnego koloru. Aby zmienić kolor na pomarańczowy po 3 minutach, aplikacja i tak musiałaby wybudzić się i wysłać `updateAppWidget`.

2. **Uzależnienie statusu głównego (`● LIVE` / `● IDLE` / `● NO PKT`):**
   * Tekst w nagłówku widgetu zależy ściśle od wieku ostatniego pakietu. `Chronometer` nie potrafi zaktualizować sąsiadującego `TextView`.

3. **Pulsowanie wskaźnika MAPPER (animacja barwna):**
   * Gdy ktoś mapuje sieć, słowo `MAPPER` pulsuje co 2 sekundy (1,2 s jasny błękit / 0,8 s ciemny błękit). `RemoteViews` nie obsługuje animacji CSS/ObjectAnimator, więc puls jest realizowany przez naprzemienną zmianę koloru przy tyknięciu sekundnika. `Chronometer` tego nie potrafi.

4. **Cykliczna zmiana mapujących i strzałki azymutu (co 3 s):**
   * Gdy sieć mapuje jednocześnie kilka osób, widget co 3 sekundy przełącza strzałkę kierunkową, kolor i odległość kolejnego mapera. Żaden widok systemowy nie zastąpi w tym pętli aplikacyjnej.

5. **Sztywny format czasu:**
   * `Chronometer` formatuje czas wyłącznie jako `MM:SS` lub `H:MM:SS` (np. `00:15`, `02:40`).
   * ObserverWidget używa dedykowanego, czytelnego formatowania radiowego:
     * poniżej minuty: `15s`, `05s`
     * powyżej minuty: `01:20s`
     * powyżej doby: `1d16h05m`

### Wniosek dot. zużycia baterii:
Przerysowywanie `RemoteViews` co 1 sekundę **wtedy, gdy ekran jest włączony i patrzymy na pulpit**, pobiera znikome ilości energii (ok. 5–10 mW wobec 1500–2500 mW pobieranych przez sam podświetlony wyświetlacz i procesor graficzny). 

Prawdziwym problemem był brak głębokiego snu **w kieszeni**. Rozwiązanie zastosowane w v2.10.6 (wyłączenie rysowania przy zgaszonym ekranie + usunięcie stałego Wakelocka) dało pełną oszczędność baterii bez psucia dynamicznych funkcji widgetu.
