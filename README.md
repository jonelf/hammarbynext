# Hammarby nästa match

En skärmsläckare för Android TV med tre rutor som studsar runt skärmen och mot varandra: Hammarbys nästa
match på Hammarbyflaggan, AIK:s nästa match på AIK-flaggan, och domaren med klockan och dagens datum på en
randig domartröja. Den är byggd som ett tillägg till
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) och syns också som ett kort på dess Home, men fungerar som
skärmsläckare med vilken launcher som helst.

- **Krockar:** Hammarby mot AIK ger en blixt; i en tredjedel av fallen ramlar ett av lagen (lika ofta vilket)
  ner till botten och ligger sex sekunder. Ett lag mot domaren: hälften av gångerna bara en studs, 40 % gult
  kort och 10 % rött kort, då laget åker ut och kommer tillbaka efter 30 sekunder.
- **Mål:** kortsidorna har ett osynligt mål i mitten (15 % av höjden). Hammarby gör mål till höger och AIK
  till vänster: konfetti i lagets färger och MÅL! mitt på skärmen.
- Allt går att ställa in med konstanterna överst i `Pitch.java`: fart, målstorlek och sannolikheter.
- **Data:** ESPN:s öppna JSON-API, samma som `api-sports.py`. Ingen nyckel behövs, men API:t är inofficiellt.
  Schemat hämtas när skärmsläckaren startar (om det sparade är äldre än 15 minuter) och sedan var 30:e minut,
  bara medan skärmsläckaren eller kortet syns. Senaste svaret sparas, så något visas direkt även utan nät.
- **Home-widget:** på cleanyourtv:s Home visas nästa match under klockan ("Hammarby IF – IK Sirius",
  "Avspark om 6h 17m", sedan "Direkt nu"). Sätt `game_day` till `se.hammarbynext.tv` i launcherns `config.xml`.
  Appen svarar via `GamesProvider` enligt launcherns game_day-kontrakt och hämtar nytt schema i bakgrunden
  när Home frågar och det sparade är äldre än 30 minuter.
- **Väder bredvid matchen:** med `info_panel` = `se.hammarbynext.tv` i launcherns `config.xml` (en lokal
  launcherändring, se nedan) visas din Netatmo-station till höger om matchen: temperatur ute med trend, och
  min/max, fukt, inne, regn i dag och vind. Rubriken är TORPET. Hämtas var 20:e minut, bara medan Home syns.
- **Behörigheter:** bara `INTERNET`. Inga bakgrundstjänster, ingen statistik.
- **Inbränning:** alla rutor rör sig hela tiden, så inget står still på skärmen.
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
TV_HOST=<tv ip> tools/venv/bin/python -I tools/b64push.py ~/projects/hammarbynext/app/build/outputs/apk/release/app-release.apk /data/local/tmp/hammarbynext.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/hammarbynext.apk; rm -f /data/local/tmp/hammarbynext.apk'
```

Tryck Home: cleanyourtv:s launcher frågar en gång om kortet ska visas.

## Netatmo

Lägg nycklarna i `.env` (ingår inte i git):

```
NETATMO_CLIENT_ID=...
NETATMO_CLIENT_SECRET=...
NETATMO_REFRESH_TOKEN=...
```

Kör sedan, en gång:

```bash
TV_HOST=<tv ip> ./setup-netatmo.sh
```

Skriptet lägger nycklarna i appens mapp på TV:n. Appen läser in dem, sparar dem privat och tar bort filen.
Netatmo byter refresh-token vid varje förnyelse och appen sparar den nya, så den i `.env` slutar gälla när appen
väl har förnyat. Kör skriptet igen med en ny token om appen någon gång blir utelåst.

Panelen kräver en launcher med `info_panel` (`InfoPanel.java` i cleanyourtv, inte uppströms).

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

- `Pitch.java`: `SPEED` (fart i andel av skärmbredden per sekund), `GOAL` (målets höjd), sannolikheterna
  `P_FALL`, `P_YELLOW` och `P_RED`, och tiderna `LIE_MS` och `OUT_MS`.
- `CardView.java`: `WIDTH` är rutornas bredd i andel av skärmen (4:3), och radernas storlekar står i `SIZE`.
- `NextMatchScene.java`: panelernas färger, `REFRESH_MS` och `ZONE`.
- `EffectsView.java`: blixten, korten och målfirandet.
- Flaggorna är `app/src/main/res/drawable-nodpi/flag.webp` och `aik_flag.jpg`; de beskärs till 4:3.

## Ta bort

```bash
adb uninstall se.hammarbynext.tv
```

Var den vald som skärmsläckare väljer du en annan i Inställningar.
