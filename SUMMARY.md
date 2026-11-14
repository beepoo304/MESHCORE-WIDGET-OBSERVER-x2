# Podsumowanie Zmian i Optymalizacji (v2.10.6)

**Gałąź:** `audit/battery-optimization-v3`  
**Data wdrożenia:** 2026-11-14  
**Wersja:** 2.10.6 (versionCode 26)

---

## Szczegółowa lista wprowadzonych modyfikacji:

1. **Wyeliminowano permanentny `PARTIAL_WAKE_LOCK` (24/7)** – blokada wybudzania nie jest już trzymana bez przerwy, lecz pobierana wyłącznie na czas trwania zapytania sieciowego (maksymalnie 30 sekund) i od razu zwalniana, co pozwala procesorowi telefonu wejść w głęboki sen (*Deep Sleep*).
2. **Wprowadzono adaptacyjne usypianie pętli w `MonitorService`** – gdy ekran telefonu jest zablokowany, usługa nie budzi procesora co 1 sekundę, lecz usypia pętlę na pełny czas pozostały do kolejnego odpytania sieci (domyślnie 15 sekund).
3. **Dodano obsługę zdarzeń ekranu (`ACTION_SCREEN_ON` / `OFF`)** – włączenie ekranu natychmiast wybudza usługę, wymusza świeże przerysowanie widgetu i wznawia płynne tykanie sekundnika tylko wtedy, gdy użytkownik faktycznie patrzy na wyświetlacz.
4. **Włączono pulę połączeń HTTP Keep-Alive** – usunięto wywołania `connection.disconnect()` z modułów `ObserverApi`, `MqttStatusApi`, `MapperApi` oraz `CompanionKeys`, zastępując je zamykaniem strumieni danych, co pozwala systemowi Android na ponowne wykorzystywanie otwartych gniazd TCP.
5. **Drastycznie zredukowano narzut na modem komórkowy i procesor** – brak konieczności wykonywania od zera kosztownych negocjacji kryptograficznych TLS (handshake) przy każdym z 6 równoległych zapytań w cyklu odciąża CPU i pozwala modemowi LTE/5G przechodzić w tryb oszczędzania energii (*RRC IDLE*).
6. **Naprawiono błąd opóźnionych powiadomień PUSH (problem 40 minut)** – dodano weryfikację wieku najnowszego pomiaru w `Alerts.evaluateMapping` oraz `evaluateRptMapping` w oparciu o próg `ACTIVE_MS` (3 minuty).
7. **Wyeliminowano wysyłanie przeterminowanych alertów po wybudzeniu telefonu** – jeśli telefon leżał w uśpieniu lub poza zasięgiem przez kilkadziesiąt minut, po pobraniu zaległych punktów z bazy mappera aplikacja odrzuca stare pingi i nie alarmuje o mapowaniu, które dawno się zakończyło.
8. **Rozwiązano problem fałszywych alarmów przy braku internetu** – wprowadzono flagę `phoneOffline`, która rozpoznaje, kiedy to telefon utracił łączność ze światem (błąd sieci na wszystkich serwerach jednocześnie).
9. **Zabezpieczono alarmy Observera i brokerów przed brakiem sieci** – w sytuacji braku internetu w telefonie aplikacja ustawia stan `UNKNOWN` zamiast `BAD`, dzięki czemu użytkownik nie otrzymuje fałszywych powiadomień o awarii stacji bazowej podczas wjazdu do tunelu lub zaniku sygnału komórkowego.
10. **Zabezpieczono rejestr przestojów (*Outages*)** – przerwy w łączności spowodowane wyłącznie brakiem internetu po stronie telefonu nie są już błędnie wpisywane do 30-dniowej historii przestojów Observera.
11. **Zoptymalizowano renderowanie widgetu w pamięci** – w klasie `WidgetRenderer` dodano pamięć podręczną (*cache*) dla bitmapy strzałki azymutu i odległości, eliminując ciągłą alokację nowych obiektów `Bitmap` i obciążenie odśmiecacza pamięci (*Garbage Collector*).
12. **Zaktualizowano konfigurację wydań i wersję aplikacji** – podbito wersję w projekcie do **v2.10.6** (kod wersji 26) oraz zaktualizowano workflow GitHub Actions, aby automatycznie budował i publikował instalator APK dla gałęzi `audit/**`.
13. **Udokumentowano cały proces w repozytorium** – utworzono dedykowaną gałąź `audit/battery-optimization-v3` oraz dołączono plik `AUDIT.md` ze szczegółowym raportem technicznym architektury i cyklu życia.
