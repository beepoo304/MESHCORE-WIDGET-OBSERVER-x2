# Podsumowanie Zmian i Optymalizacji (v2.10.6)

**Gałąź:** `audit/battery-optimization-v3`  
**Data wdrożenia:** 2026-11-14  
**Wersja:** 2.10.6 (versionCode 26)

---

## Szczegółowa lista wprowadzonych modyfikacji:

1. **Wyeliminowano permanentny `PARTIAL_WAKE_LOCK` (24/7)** – usługa `MonitorService` nie trzyma już blokady procesora w nieskończoność. Krótkotrwały wakelock pobierany jest wyłącznie wewnątrz `ObserverRefresh.cycle` na czas trwania transferu sieciowego (maksymalnie 15 s) i od razu zwalniany po zakończeniu, co pozwala procesorowi urządzenia przejść w głęboki sen (*Deep Sleep*).
2. **Płynne tykanie bez zamrażania po wybudzeniu ekranu** – pętla `MonitorService` tyka równo co 1 sekundę na granicy pełnej sekundy czasu systemowego, a rysowanie widoku `renderAll` jest wykonywane wyłącznie, gdy ekran jest włączony (`power.isInteractive != false`). Eliminuje to zbędne narzuty IPC przy zablokowanym telefonie, a po wybudzeniu ekranu liczniki natychmiast ożywają bez żadnego opóźnienia czy zamrożenia.
3. **Stabilne połączenia sieciowe** – zachowano prawidłowe zamykanie gniazd `HttpURLConnection.disconnect()`, zapobiegając zawieszaniu się gniazd w puli i błędom timeoutu.
4. **Naprawiono błąd opóźnionych powiadomień PUSH (problem 40 minut)** – dodano weryfikację wieku najnowszego pomiaru w `Alerts.evaluateMapping` oraz `evaluateRptMapping` w oparciu o próg `ACTIVE_MS` (3 minuty).
5. **Wyeliminowano wysyłanie przeterminowanych alertów po wybudzeniu telefonu** – jeśli telefon leżał w uśpieniu lub poza zasięgiem przez kilkadziesiąt minut, po pobraniu zaległych punktów z bazy mappera aplikacja odrzuca stare pingi i nie alarmuje o mapowaniu, które dawno się zakończyło.
6. **Rozwiązano problem fałszywych alarmów przy braku internetu** – wprowadzono flagę `phoneOffline`, która rozpoznaje, kiedy to telefon utracił łączność ze światem (błąd sieci na wszystkich serwerach jednocześnie).
7. **Zabezpieczono alarmy Observera i brokerów przed brakiem sieci** – w sytuacji braku internetu w telefonie aplikacja ustawia stan `UNKNOWN` zamiast `BAD`, dzięki czemu użytkownik nie otrzymuje fałszywych powiadomień o awarii stacji bazowej podczas wjazdu do tunelu lub zaniku sygnału komórkowego.
8. **Zabezpieczono rejestr przestojów (*Outages*)** – przerwy w łączności spowodowane wyłącznie brakiem internetu po stronie telefonu nie są już błędnie wpisywane do 30-dniowej historii przestojów Observera.
9. **Zoptymalizowano renderowanie widgetu w pamięci** – w klasie `WidgetRenderer` dodano pamięć podręczną (*cache*) dla bitmapy strzałki azymutu i odległości, eliminując ciągłą alokację nowych obiektów `Bitmap` i obciążenie odśmiecacza pamięci (*Garbage Collector*).
10. **Zaktualizowano konfigurację wydań i wersję aplikacji** – podbito wersję w projekcie do **v2.10.6** (kod wersji 26) oraz zaktualizowano workflow GitHub Actions, aby automatycznie budował i publikował instalator APK dla gałęzi `audit/**`.
11. **Udokumentowano cały proces w repozytorium** – utworzono dedykowaną gałąź `audit/battery-optimization-v3` oraz dołączono plik `AUDIT.md` ze szczegółowym raportem technicznym architektury i cyklu życia.
