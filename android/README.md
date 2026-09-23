# Mój dom — aplikacja Android

Pulpit z danymi z domu na jednym ekranie. **Etap 1:** fotowoltaika APsystems. Pompa ciepła (Panasonic) i pokoje (tado) dojdą w kolejnych etapach.

Specyfikacja, plan i zadania: [`specs/001-home-energy-dashboard/`](../specs/001-home-energy-dashboard/).

## Budowanie

Wymagania: JDK 17 oraz Android SDK (platform 34, build-tools 34.0.0). Ścieżkę do SDK podaje `sdk.dir` w `local.properties` albo zmienna `ANDROID_HOME`.

```bash
cd android
./gradlew testDebugUnitTest   # testy jednostkowe; nigdy nie łączą się z prawdziwym API
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (podpisany kluczem debug)
```

## Instalacja na telefonie

1. Na telefonie włącz *Opcje programisty → Debugowanie USB*.
2. Podłącz telefon i zainstaluj aplikację:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Można też skopiować plik APK na telefon i otworzyć go, zezwalając na instalację z nieznanych źródeł.

## Dane dostępowe APsystems

Aplikacja korzysta z oficjalnego APsystems OpenAPI. Na ekranie **Konta** podaj App ID, App Secret i System ID (SID). Dane są szyfrowane kluczem z Android Keystore i nie trafiają do kopii zapasowej.

**Tylko wersja debug:** formularz można wstępnie wypełnić wpisami w `android/local.properties`. Ten plik jest w `.gitignore`, więc **nigdy go nie commituj**:

```properties
apsystems.appId=…
apsystems.appSecret=…
apsystems.sid=…
```

Uwaga: taki APK debug zawiera te dane w środku, więc nie udostępniaj go nikomu. Wersja release ich nie zawiera.

## Limit zapytań

APsystems pozwala na **1000 zapytań miesięcznie** na App ID, wspólnie ze wszystkimi innymi narzędziami używającymi tego samego klucza. Dlatego aplikacja:

- domyślnie zużywa najwyżej **800** zapytań miesięcznie (limit można zmienić w *Konta → Budżet zapytań*, w zakresie 100–1000);
- nie odpytuje w nocy, bo wschód i zachód słońca liczy dla lokalizacji z ustawień;
- odświeża dane w dzień mniej więcej co 15–40 minut, zależnie od pory roku, i tylko wtedy, gdy jest otwarta;
- przy ręcznym odświeżeniu (przeciągnięcie w dół) pobiera dane tylko wtedy, gdy są starsze niż 10 minut;
- po przekroczeniu limitu (kody 2005, 7001–7003) robi przerwę 15 → 30 → 60 min… (maksymalnie 6 h).

Licznik wykorzystanych zapytań widać w *Konta → Budżet zapytań*.
