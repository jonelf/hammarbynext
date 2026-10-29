# hammarbynext: en hemmagjord Android TV-app

En app (`se.hammarbynext.tv`) för en Sony BRAVIA med Android TV 9 och [cleanyourtv](https://github.com/Gathin23/cleanyourtv):s
launcher. Den började som "Hammarbys nästa match" och gör nu fyra saker:

| Del | Vad den gör | Var den syns |
|---|---|---|
| [Skärmsläckaren](#skärmsläckaren) | Hammarby, AIK och domaren studsar runt skärmen med nästa match, klockan och datumet; blixtar, kort och mål | TV:ns skärmsläckare, och rutan/kortet på Home |
| [Home-panelerna](#home-panelerna) | Hammarbys nästa match och vädret från Netatmo-stationen på torpet, under klockan | cleanyourtv:s Home |
| [Bevakningen](#bevakningen-vma-och-polisen) | VMA och Polisens händelser i Stockholm som notis ovanpå det som visas, även Netflix | Över alla appar |
| [Appen](#appen) | Skärmsläckarens scen direkt, tills Back | Rutan "Bajen" i docken och i app-listan |

Allt räknar i svensk tid (`Europe/Stockholm`), oavsett vilken tidszon TV:n har. `api-sports.py` är
originalskriptet som visar Hammarbys matcher i terminalen, från samma ESPN-API.

## Delarna

### Skärmsläckaren

Tre rutor studsar mot skärmens kanter och mot varandra:

- **Hammarby** på Hammarbyflaggan och **AIK** på AIK-flaggan, med lagets nästa match: lag, avspark, turnering,
  arena och nedräkning.
- **Domaren** på en randig svartvit domartröja, med klockan och dagens datum.

Det som händer när de möts:

- **Hammarby mot AIK:** en blixt. I en tredjedel av fallen ramlar ett av lagen (lika ofta vilket) ner till botten,
  ligger lutat i sex sekunder och studsar sedan upp igen. Annars bara en studs.
- **Ett lag mot domaren:** hälften av gångerna en studs, 40 % gult kort och 10 % rött kort. Vid rött kort åker
  laget ut ur bild och kommer tillbaka från samma sida efter 30 sekunder.
- **Mål:** kortsidorna har ett osynligt mål i mitten (15 % av höjden). Hammarby gör mål till höger och AIK till
  vänster: konfetti i lagets färger ur målet och MÅL! mitt på skärmen.

Allt rör sig hela tiden, så inget bränns in.

### Home-panelerna

Två paneler under klockan på cleanyourtv:s Home:

- **Matchen** ("HAMMARBY IF – IK SIRIUS", "Avspark om 6h 17m", sedan "Direkt nu") via launcherns `game_day`.
  Appen svarar på launcherns kontrakt med `GamesProvider`.
- **Vädret, TORPET**, till höger om matchen: temperatur ute med trend, min/max, fukt, inne, regn i dag och vind
  från Netatmo-stationen. Den här panelen (`info_panel`) är en lokal ändring i launchern, `InfoPanel.java` i
  cleanyourtv, och finns inte uppströms. Väder äldre än två timmar visas inte.

### Bevakningen: VMA och Polisen

En förgrundstjänst (`WatchService`, appens enda bakgrundstjänst) visar nya händelser som en notis ovanpå det
som syns:

- **VMA** från Sveriges Radio, som gäller Stockholms kommun, länet eller hela landet: en stor röd ruta överst i
  60 sekunder. Upphävanden och tester visas inte.
- **Polisens händelser** i Stockholm, alla typer: en liten ruta nere till höger i 10 sekunder, till exempel
  "🔥 Mordbrand · Stockholm". Polisen publicerar ofta timmar efter händelsen: äldre än sex timmar visas inte, och
  högst tre per hämtning.

Varje händelse visas en gång. Notisen tar aldrig fokus, så fjärrkontrollen styr appen under som vanligt. VMA går
före Polisen. Tjänsten startar när TV:n startar och när skärmsläckaren, appen eller Home-panelen används.

### Appen

Rutan "Bajen" (bannern är en Hammarbyspelare) öppnar skärmsläckarens scen direkt, tills Back. På cleanyourtv:s
Home är den också ett kort, som launchern frågar om en gång.

## Datakällor och hur ofta de frågas

Inget hämtas när inget syns: bevakningen frågar bara medan skärmen är på, resten bara medan den del som
använder datan visas.

| Källa | Används till | Hur ofta | Nyckel |
|---|---|---|---|
| [ESPN](https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/2495/schedule?fixture=true), lag 2495 (Hammarby) och 994 (AIK) | Skärmsläckaren, appen | Vid start om det sparade är äldre än en timme, sedan varannan timme; ett anrop per lag | Ingen; inofficiellt API |
| ESPN, bara Hammarby | Matchpanelen på Home | När Home frågar och det sparade är äldre än två timmar, högst var femte minut | Ingen |
| [Netatmo](https://dev.netatmo.com/apidocumentation/weather) `getstationsdata` | Väderpanelen | Var 20:e minut medan Home syns | OAuth, se [Netatmo](#netatmo) |
| [Sveriges Radio VMA](https://vmaapi.sr.se/api/v3/alerts) | Bevakningen | Varannan minut | Ingen |
| [Polisen](https://polisen.se/api/events?locationname=Stockholm) | Bevakningen | Var tionde minut | Ingen |

Misslyckas en hämtning försöker appen igen efter några minuter, Polisen vid nästa ordinarie fråga. Senaste svaret sparas, så något visas direkt
även utan nät. När en match är slut hämtas schemat en extra gång, så nästa match syns direkt.

## Behörigheter

- `INTERNET`: för källorna ovan.
- `SYSTEM_ALERT_WINDOW`: notiserna ovanpå andra appar. På TV ges den med ADB, se [Ställa in](#ställa-in).
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` och `RECEIVE_BOOT_COMPLETED`: bevakningen, och att den
  startar när TV:n startar.

Ingen statistik och inga annonser. `GamesProvider` är exporterad utan behörighet, som launcherns kontrakt kräver,
och lämnar bara ut det Home ändå visar.

## Bygga

Direkt på Macen med JDK 17 och Homebrews Android SDK (platform 34, build-tools 34.0.0). Standard-`java` är
nyare, så peka ut JDK 17:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

`local.properties` pekar ut SDK:n (`sdk.dir=/opt/homebrew/share/android-commandlinetools`). APK:n hamnar i
`app/build/outputs/apk/release/app-release.apk`.

- **Signering:** release signeras med debug-nyckeln i `~/.android/debug.keystore`, samma som launchern på TV:n
  (kopierad från cleanyourtv:s Docker-volym `cleanyourtv-gradle`). En APK med en annan nyckel går inte att
  installera över den som finns, och launchern frågar om kortet igen.
- **Docker:** `./docker-build.sh` bygger i cleanyourtv:s Docker-avbildning med samma nyckel, om Macen saknar SDK.
- **Tester:** `PitchTest` kör skärmsläckarens fysik i tre timmar simulerad tid och provar mål och utvisning;
  `AlertsTest` tolkar riktiga svar från VMA och Polisen (`app/src/test/resources/`).

## Installera

Från cleanyourtv-mappen, med dess ADB-klient (större filer måste gå via `b64push.py`):

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/b64push.py ~/projects/hammarbynext/app/build/outputs/apk/release/app-release.apk /data/local/tmp/hammarbynext.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/hammarbynext.apk; rm -f /data/local/tmp/hammarbynext.apk'
```

Eller med vanlig `adb`: `adb connect <tv ip>` och `adb install -r app/build/outputs/apk/release/app-release.apk`.
Installationen sker i bakgrunden; TV:n byter inte ingång.

## Ställa in

Varje steg görs en gång. ADB-kommandona nedan kan också köras med `tools/tv.py '<kommando>'` från cleanyourtv.

### Skärmsläckaren

Inställningar > Enhetsinställningar > Skärmsläckare > **Hammarby nästa match**, eller:

```bash
adb shell settings put secure screensaver_components se.hammarbynext.tv/.NextMatchDream
adb shell settings put secure screensaver_enabled 1
adb shell am start -n com.android.systemui/.Somnambulator   # visa den direkt
```

TCL-TV kan vägra starta en sidladdad skärmsläckare (`Unable to bind dream service`) tills appen fått autostart:
`adb shell cmd appops set se.hammarbynext.tv AUTO_START allow`.

### Home-panelerna och docken

I cleanyourtv:s `launcher/app/src/main/res/values/config.xml`, och bygg och installera launchern efteråt:

```xml
<string name="game_day" translatable="false">se.hammarbynext.tv</string>
<string name="info_panel" translatable="false">se.hammarbynext.tv</string>
```

Appen ligger i docken genom att `se.hammarbynext.tv` står i `dock_apps` där.

### Netatmo

Lägg nycklarna i `.env` (ingår inte i git). Access token behövs inte; appen hämtar en med refresh-token.

```
NETATMO_CLIENT_ID=...
NETATMO_CLIENT_SECRET=...
NETATMO_REFRESH_TOKEN=...
```

```bash
TV_HOST=<tv ip> ./setup-netatmo.sh
```

Skriptet lägger nycklarna i appens mapp på TV:n; appen läser in dem, sparar dem privat och tar bort filen.
Netatmo byter refresh-token vid varje förnyelse och appen sparar den nya, så den i `.env` slutar gälla. Kör
skriptet igen med en ny token om appen någon gång blir utelåst. Använd en egen token för TV:n: samma token på två
ställen låser ute det ena.

### Bevakningen

```bash
adb shell appops set se.hammarbynext.tv SYSTEM_ALERT_WINDOW allow
```

Prova notiserna utan en riktig händelse, gärna medan Netflix spelar:

```bash
adb shell am start-foreground-service -n se.hammarbynext.tv/.WatchService --es test vma       # Sveriges Radios testflöde
adb shell am start-foreground-service -n se.hammarbynext.tv/.WatchService --es test polisen   # Polisens senaste
```

## Ändra

| Fil | Vad |
|---|---|
| `Pitch.java` | Skärmsläckarens fysik: `SPEED`, `GOAL`, sannolikheterna `P_FALL`, `P_YELLOW`, `P_RED`, tiderna `LIE_MS` och `OUT_MS` |
| `CardView.java` | Rutornas bredd (`WIDTH`, andel av skärmen, 4:3) och textstorlekar (`SIZE`) |
| `EffectsView.java` | Blixten, korten och målfirandet |
| `NextMatchScene.java` | Panelfärgerna, `REFRESH_MS` och tidszonen |
| `MatchSource.java` | Lagen och deras ESPN-id (`Team`) |
| `NetatmoSource.java` | Väderpanelens text, rubriken TORPET |
| `GamesProvider.java` | Det Home får: matchen och vädret, och hur ofta de hämtas |
| `Alerts.java` | Bevakningens källor och filter (Stockholms geokoder för VMA) |
| `WatchService.java` | Hur ofta bevakningen frågar och hur många notiser som visas |
| `OverlayNotice.java` | Notisernas utseende, plats och tid på skärmen |

Bilderna ligger i `app/src/main/res/drawable-nodpi/`: `flag.webp` (Hammarby), `aik_flag.jpg`, `banner.jpg` och
`icon.jpg`. Flaggorna beskärs till 4:3.

## Ta bort

```bash
adb uninstall se.hammarbynext.tv
```

Välj sedan en annan skärmsläckare i Inställningar, och töm `game_day`, `info_panel` och raden i `dock_apps` i
launcherns `config.xml`.
