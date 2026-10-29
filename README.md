# Hammarby nästa match

En skärmsläckare för Android TV: Hammarbyflaggan studsar långsamt runt skärmen med klockan, dagens datum och
nästa match ovanpå (lag, avspark, turnering, arena och nedräkning). Den är byggd som ett tillägg till
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) och syns också som ett kort på dess Home, men fungerar som
skärmsläckare med vilken launcher som helst.

- **Data:** ESPN:s öppna JSON-API, samma som `api-sports.py`. Ingen nyckel behövs, men API:t är inofficiellt.
  Schemat hämtas när skärmsläckaren startar (om det sparade är äldre än 15 minuter) och sedan var 30:e minut,
  bara medan skärmsläckaren eller kortet syns. Senaste svaret sparas, så något visas direkt även utan nät.
- **Behörigheter:** bara `INTERNET`. Inga bakgrundstjänster, ingen statistik.
- **Inbränning:** hela flaggan rör sig hela tiden, så inget står still på skärmen.
- **Tid:** svensk tid (`Europe/Stockholm`), oavsett vilken tidszon TV:n har.

## Bygga

JDK 17 och Android SDK, platform 34 och build-tools 34.0.0. Peka ut SDK:n med `ANDROID_HOME` eller med
`sdk.dir=/sökväg/till/sdk` i `local.properties`.

```bash
./gradlew --no-daemon assembleRelease
```

APK:n hamnar i `app/build/outputs/apk/release/app-release.apk`.

## Installera

Med `adb`:

```bash
adb connect <tv ip>
adb install -r app/build/outputs/apk/release/app-release.apk
```

Eller med cleanyourtv:s egen ADB-klient, från cleanyourtv-mappen:

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py --push ~/projects/hammarbynext/app/build/outputs/apk/release/app-release.apk /data/local/tmp/hammarbynext.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/hammarbynext.apk; rm -f /data/local/tmp/hammarbynext.apk'
```

Tryck Home: cleanyourtv:s launcher frågar en gång om kortet ska visas.

## Välja som skärmsläckare

Inställningar > Enhetsinställningar > Skärmsläckare > **Hammarby nästa match** (menyernas namn varierar mellan
TV-märken), eller:

```bash
adb shell settings put secure screensaver_components se.hammarbynext.tv/.NextMatchDream
adb shell settings put secure screensaver_enabled 1
```

Visa den direkt, utan att vänta:

```bash
adb shell am start -n com.android.systemui/.Somnambulator
```

**TCL:** vissa TCL-TV startar inte en sidladdad skärmsläckare (i loggen: `Unable to bind dream service`) förrän
appen har fått autostart:

```bash
adb shell cmd appops set se.hammarbynext.tv AUTO_START allow
```

## Ändra utseendet

- `NextMatchScene.java`: `SPEED` (fart i andel av skärmbredden per sekund), `REFRESH_MS` och `ZONE`.
- `FlagCardView.java`: `onMeasure` styr flaggans storlek (halva skärmbredden). `PANEL` är panelens färg och
  genomskinlighet, och radernas storlekar står i `onDraw` som andelar av flaggans bredd.
- Flaggan är `app/src/main/res/drawable-nodpi/flag.webp`. En annan bild i 4:3 kan läggas där.

## Ta bort

```bash
adb uninstall se.hammarbynext.tv
```

Var den vald som skärmsläckare väljer du en annan i Inställningar.
