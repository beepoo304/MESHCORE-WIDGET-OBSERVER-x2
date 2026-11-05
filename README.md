# ObserverWidget

Androidowy widget domowy + aplikacja do monitorowania urządzenia **Observer** (node nasłuchujący, u nas pełniący też rolę repeatera) w sieci [MeshCore](https://github.com/meshcore-dev/MeshCore) (Polska Południe, preset **SF6**). Aplikacja nie ma żadnych danych Observera wpisanych na stałe — wszystko konfiguruje użytkownik (pełny hash 64 znaki hex).

Dokumentacja jest celowo szczegółowa, żeby kolejne osoby (i asystenci AI) mogły szybko zrozumieć, co i jak działa, zanim cokolwiek zmienią.

## Co robi aplikacja

1. **Widget na ekranie głównym** pokazuje:
   - hash (skrót) i nazwę Observera, status LIVE/IDLE/NO PKT,
   - dla dwóch brokerów (MQTT1/MQTT2): pakiety na godzinę, total packets, wiek ostatniego pakietu (kolor: zielony/pomarańczowy/czerwony wg progów minutowych),
   - licznik sekund do następnego odświeżenia (tyka co 1 s).
2. **Wykrywa mapowanie** (od v2.2.0): gdy ktoś mapuje sieć maperem [meshcoresouth.pl/mapper](https://meshcoresouth.pl/mapper/) i nasz Observer odbierze pingi mapowania:
   - pulsujące słowo **MAPPER** (jasnoniebieskie) w nagłówku widgetu, na środku między MESHCORE a licznikiem odświeżania — widoczne tylko, gdy ktoś faktycznie mapuje (ostatni ping ≤ 3 min; znika szybko po zakończeniu mapowania),
   - obok MAPPER **strzałka kierunku w kolorze mapującego** (obracana o azymut, 8 kierunków) z odległością mapera od Observera, np. `→ 1,6km`; 1. mapujący = czerwony, 2. = żółty, 3. = fioletowy, kolejni dalej z palety (`WidgetRenderer.MAPPER_ARROW_COLORS`, cyklicznie); przy kilku mapujących strzałka zmienia mapera co 3 s w kółko (ostatni ping każdego z nich), aż mapowanie zniknie (3 min po ostatnim pingu); pozycja Observera z `api.php?action=repeaters` (cache w prefs, tydzień),
   - klik na MAPPER otwiera aplikację z **kompaktową kartą mapowania (2–3 linijki)**, widoczną 24 h od ostatniego pinga: tytuł + klucz (pierwsze 4 znaki + ikonka kopiowania pełnego), mapujący z kierunkiem (16 sektorów) i odległością od Observera (nick w kolorze strzałki tego mapera na widgecie), liczba pingów + czas ostatniego pinga + SNR u Observera,
   - **powiadomienie PUSH** z nickiem mapującego (raz na sesję i przy zmianie mapującego).
3. **Powiadomienia PUSH o awariach**: Observer nie wysyła pakietów (natychmiast po przekroczeniu czerwonego progu), MQTT1/MQTT2 martwe (z opóźnieniem i cooldownem).
4. **Aplikacja** (panel OBSERVER): karta mapowania, karty brokerów, start/stop monitoringu; osobny ekran Settings.

## Źródła danych (API)

| Co | Endpoint | Uwagi |
|---|---|---|
| Dane Observera | `GET {host}/api/observers/{hash64}` | hosty: MQTT1/MQTT2 + fallback (CoreScope). Odpowiedź: `id, name, model, firmware, packet_count, packetsLastHour, last_packet_at, last_seen`. ID rozróżnia wielkość liter — próbujemy WIELKIMI, przy 404 małymi. |
| Zdrowie brokera | `GET {host}/api/mqtt/status` | `sampleAt`, `watchdogLastTickUnix`, `sources[]` (connected, lastPacketUnix). GOOD/BAD/UNKNOWN. |
| **Mapowanie** | `GET https://meshcoresouth.pl/mapper/api.php?action=points&since={id}&limit=20000` | Publiczna baza mapera. Patrz niżej. |

### Format danych mapera (kluczowe!)

Odpowiedź: `{"ok":true,"rev":N,"last":ID,"more":bool,"points":[...],"users":{"1":"nick",...},"car":[...]}`

Punkt = tablica: `[id, unix_s, lat, lon, rpt, snr, usnr, rssi, user, acc, nf, prof]`

- `rpt` — 16-znakowy ID repeatera = **pierwsze 16 znaków klucza publicznego** (małe litery). Punkt z `rpt` równym pierwszym 16 znakom hasha naszego Observera = **Observer odebrał ping mapowania i odpowiedział** (`null` = ping bez odpowiedzi).
- `snr` — SNR odbioru: jak companion mapera słyszy repeatera (dB).
- `usnr` — SNR u repeatera: jak nasz Observer słyszy companiona (dB).
- `rssi` — RSSI odbioru u companiona (dBm), `user` — ID konta (nick w `users`), `acc` — dokładność GPS (m), `nf` — podłoże szumu (dB).
- `since` = increment po ID punktu (pole `last` z odpowiedzi); `rev` zmienia się po usunięciu danych przez moderatora → wtedy resetujemy kursor i nie traktujemy starych punktów jako nowych.
- Mapper pinguje co 20–120 s, wysyła pomiary do serwera kolejkowo (do 15 s opóźnienia).

Sesja mapowania = pomiary połączone przerwami ≤ 5 min (`MapperApi.SESSION_GAP_MS`). Próg „aktywnego mapowania” (widoczność MAPPER na widgecie) = ostatni ping ≤ 3 min temu (`MapperApi.ACTIVE_MS`).

## Architektura (pliki w `app/src/main/java/pl/meshcore/observerwidget/`)

- `Config.kt` — wszystkie ustawienia w SharedPreferences (`config`), walidacja hasha (64 hex, uppercase), `baseUrl()`, `displayHost()`, progi kolorów, przełączniki alertów, kursor mapera (`mapperLastId`, `mapperRev`).
- `Models.kt` — `ObserverSnapshot`, `FetchResult`, `WidgetData` (stan rysowany), `MapperPing` (pomiar mapera), `MappingState` (stan mapowania), `Level`, `Health`.
- `ObserverApi.kt` — klient `/api/observers/{id}`: parsowanie JSON (wiele wariantów nazw pól), parsowanie czasu (epoch s/ms, ISO-8601, `yyyy-MM-dd HH:mm:ss` UTC), korekta zegara z nagłówka HTTP `Date` (`clockOffset`, ignoruje < 5 s).
- `MqttStatusApi.kt` — ocena zdrowia brokera; próby z `networkError` rozróżniają „broker martwy” od „telefon offline”.
- `MapperApi.kt` — klient API mapera: `poll(sinceId, timeoutMs)` → `PollResult(lastId, rev, more, points, users)`; `parse()` czysty i testowalny; `shortId()` = pierwsze 16 znaków klucza.
- `MapperMonitor.kt` — stan mapowania: trzyma nasze pingi w pamięci (24 h), cache nicków, pozycję Observera z bazy repeaterów (tydzień), buduje sesję. `poll()` wołane z `ObserverRefresh.collect()`; pierwsza tura ustawia tylko kursor (historia nie powiadamia). `stateOf()` to czysta, testowalna logika sesji.
- `GeoMath.kt` — czysta geografia: `distanceKm` (wielki koło), `bearingDeg` (azymut 0°=N), `compassLabel` (16 sektorów), `km`/`kmShort` (format z polskim przecinkiem).
- `ObserverRefresh.kt` — serce: `collect()` pobiera równolegle oba brokery + statusy + fallback + mapera; `RefreshGate` chroni przed nakładaniem cykli; harmonogram wyrównany do pełnej sekundy; zamraża wiek pakietu brokera przy padnięciu jego API; `lastData` w pamięci.
- `MonitorService.kt` — usługa pierwszoplanowa (specialUse): tyka co 1 s (tylko rysowanie przy włączonym ekranie; pobieranie danych zawsze), częściowy wakelock 10 min odnawiany w pętli. AlarmManager tylko rezerwa, WorkManager co 15 min zapas.
- `WidgetRenderer.kt` — RemoteViews; warianty compact/full; słowo MAPPER w nagłówku widgetu pulsuje kolorem (`mappingIconOn`, okres 2 s: 1,2 s jasny / 0,8 s przyciemniony niebieski).
- `Alerts.kt` + `AlertEngine.kt` — powiadomienia: Observer (natychmiast), MQTT1/2 (opóźnienie + cooldown, automat stanów w `AlertEngine`), mapowanie (`evaluateMapping`: raz na sesję i przy zmianie mapującego; stan w prefs `alert_state`, klucze `map_user`/`map_last`). Kanały: `alerts_observer/mqtt1/mqtt2/mapping`, ID 1001–1004.
- `MainActivity.kt` — panel OBSERVER: karty (mapowanie z ikoną kopiowania pełnego klucza, MQTT1, MQTT2), ticker 1 s; `mappingIntent()` = PendingIntent z `EXTRA_SHOW_MAPPING` (klik ikony M na widgecie).
- `SettingsActivity.kt` — konfiguracja: hash, hosty, timeout, interwał 2–60 s, progi, alerty (w tym mapowanie), test API. Zmiana hasha resetuje stan mapera (`MapperMonitor.reset()`, kursor).
- `CopyKeyReceiver.kt` — kopiowanie pełnego klucza do schowka (widget i aplikacja).
- `TimeFormat.kt`, `Status.kt`, `WidgetLayout.kt`, `Insets.kt`, `RefreshWorker.kt`, `ObserverWidgetProvider.kt` — formaty czasu, progi kolorów, wybór layoutu wg rozmiaru widgetu, edge-to-edge, WorkManager, receiver widgetu.

Layouty widgetu: `res/layout/widget_observer.xml` (full) i `widget_observer_compact.xml` — **te same id view**, zmiany trzeba wprowadzać w obu. Wskaźnik mapowania to TextView `mapping_icon` (słowo „MAPPER”) w wierszu nagłówka, między MESHCORE a licznikiem odświeżania; obok niego ImageView `mapping_arrow` (bitmapa: strzałka w kolorze mapującego obrócona o azymut + odległość, rysowana w `WidgetRenderer.mappingArrowBitmap`, bo RemoteViews nie obracają drawabli).

## CI/CD (`.github/workflows/`)

- Build: testy jednostkowe (Robolectric) → `assembleDebug`/`assembleRelease` → APK do artefaktu i **GitHub Release „latest”** (stały klucz podpisu w repo, więc APK instaluje się jako aktualizacja). Stabilny link do najnowszego APK: `https://github.com/beepoo304/observerWidget_newPLan/releases/latest/download/ObserverWidget.apk`
- Dodatkowy job: test widgetu na prawdziwym emulatorze (AppWidgetHost, API 34).

## Jak zbudować

```bash
./gradlew --no-daemon testDebugUnitTest   # testy jednostkowe
./gradlew --no-daemon assembleRelease     # APK (podpisany kluczem ci)
```

Wymagane: JDK 17, Android SDK (compileSdk 35, minSdk 26, targetSdk 35). Testy jednostkowe nie potrzebują emulatora.

## Konwencje (ważne przy zmianach)

- Komentarze i UI **po polsku** (użytkownik jest Polakiem; komunikaty widgetu/aplikacji po polsku).
- Brak bibliotek sieciowych — tylko `HttpURLConnection` + `org.json` (testy: `org.json:json` na JVM).
- Logika czysta (bez Androida) jest wydzielona, żeby dało się ją testować zwykłym JUnit (`MapperApi.parse`, `MapperMonitor.stateOf`, `AlertEngine`, `TimeFormat`, `Status`).
- RemoteViews nie animują — puls = naprzemienna jasność przy tyknięciu 1 s z `MonitorService`.
- `WidgetData` jest kopiowane w wielu miejscach — nowe pola muszą mieć wartości domyślne.
- Przy zmianie Observera w Settings resetujemy stan mapera (kursor `mapperLastId`, pozycja RPT), inaczej sesje się mieszają.

## Historia wersji

- **2.3.2** — ikona wieży widoczna w pasku statusu i na ekranie blokady (kanał monitoringu LOW zamiast MIN; nowy ID kanału, bo Android nie pozwala podnieść ważności istniejącego).
- **2.3.1** — kolor strzałki mapera wg mapującego (czerwony/żółty/fioletowy/…, zmiana co 3 s w kółko), kompaktowa karta mapowania (2–3 linijki, nadal 24 h, nick w kolorze strzałki mapera), drobne poprawki.
- **2.3.0** — strzałka kierunku mapera na widgecie (czerwona, azymut + odległość, zmiana mapera co 3 s), kierunek i odległość zamiast współrzędnych w karcie mapowania (dla każdego mapującego), karta mapowania widać 24 h od ostatniego pinga; pozycja Observera z bazy repeaterów mapera.
- **2.2.1** — słowo MAPPER w nagłówku widgetu zamiast ikonki M, szybsze czyszczenie (3 min), przewijanie panelu aplikacji, karta mapowania chowa się po 30 min, push raz na sesję mapującego.
- **2.2.0** — wykrywanie mapowania (mapper meshcoresouth.pl): pulsująca niebieska ikona M na widgecie, karta mapowania w aplikacji, push z nickiem mapującego; alerty o mapowaniu w ustawieniach (domyślnie włączone).
- **2.1.0** — odświeżanie przy wyłączonym ekranie, natychmiastowy push z nazwą brokera, zamrożony wiek pakietu przy padnięciu API brokera.
- wcześniejsze — patrz `git log`.
