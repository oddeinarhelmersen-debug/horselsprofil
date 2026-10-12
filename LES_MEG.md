# Hørselsprofil – Android-app for Pixel 6

Appen tester hørselen på hvert øre med EarPods i ørene og lagrer en lydprofil. Profilen brukes på to måter:

- **Musikk og annen lyd** (Spotify, YouTube, podkaster …): gjennom Androids egen equalizer (DynamicsProcessing), med egen kurve for venstre og høyre øre.
- **Mikrofonlytting**: mikrofonen på ledningen fanger opp lyden rundt deg, og appen spiller den forsterket inn i øreproppene. Den har egen equalizer per øre, en kompressor mot brå lyder og en begrenser som beskytter ørene.

## Hva du trenger

- Pixel 6
- EarPods med **USB-C-plugg**, eller EarPods med 3,5 mm-plugg og en USB-C-til-3,5 mm-adapter **som støtter mikrofon** (Googles egen adapter gjør det)

## Bygge appen: velg én av to veier

### Vei A: Gratis på GitHub (ingen installasjon på PC-en)

1. Lag en gratis konto på https://github.com hvis du ikke har en.
2. Trykk **+** øverst til høyre og velg **New repository**. Gi det navnet `horselsprofil`, velg **Private** og trykk **Create repository**.
3. Trykk lenken **uploading an existing file** på siden som kommer opp.
4. Pakk ut zip-filen på PC-en. Åpne mappen `Horselsprofil` og dra **alt innholdet** inn i nettleservinduet. Trykk **Commit changes**.
5. **Sjekk byggeoppskriften.** Den ligger i den skjulte mappen `.github` og blir av og til ikke med når du drar filer. Gå til fanen **Actions**. Står det ingenting om «Bygg APK» der, gjør du dette:
   - Trykk **set up a workflow yourself**.
   - Kall filen `bygg-apk.yml`.
   - Lim inn innholdet fra `.github/workflows/bygg-apk.yml`. Åpne filen med Notisblokk for å kopiere det.
   - Trykk **Commit changes**.
6. Vent på bygget. Under **Actions** blir kjøringen grønn etter 3–5 minutter.
7. På telefonen går du til `https://github.com/<ditt-brukernavn>/horselsprofil/releases`. Logg inn, og last ned **Horselsprofil.apk**.

### Vei B: Android Studio på PC-en

1. Installer Android Studio fra https://developer.android.com/studio.
2. Velg **File → Open** og deretter mappen `Horselsprofil`. Vent til Gradle er ferdig. Første gang tar det noen minutter.
3. Slå på utviklermodus på telefonen: **Innstillinger → Om telefonen**, og trykk 7 ganger på **Delversjonsnummer**.
4. Slå på USB-feilsøking: **Innstillinger → System → Utvikleralternativer → USB-feilsøking**.
5. Koble telefonen til PC-en med kabel, og godta spørsmålet som dukker opp på telefonen.
6. Trykk den grønne ▶-knappen (**Run**) i Android Studio. Appen installeres og starter.

## Installere APK-filen (vei A)

1. Åpne den nedlastede `Horselsprofil.apk` på telefonen.
2. Telefonen spør om du vil tillate installasjon fra nettleseren eller Filer. Trykk **Innstillinger**, slå på **Tillat fra denne kilden**, og gå tilbake.
3. Trykk **Installer**. Hvis Google Play Protect advarer om en ukjent app, velger du **Installer likevel**. Advarselen kommer fordi appen ikke er lastet ned fra Play Butikk.

## Bruk

1. **Hørselstest**: gjør den i et stille rom. Appen setter mediavolumet fast på 60 % under testen og setter det tilbake etterpå. Når testen er ferdig, gir du den et navn. Alle tester lagres, og den som er merket «I bruk» styrer profilen.
2. **Sikkerhetskopi**: «Ta sikkerhetskopi» lager en tekst med alle testene, som du kan sende til deg selv. «Hent sikkerhetskopi» leser den inn igjen.
3. **Lydprofil**: forsterkningen regnes ut fra testen. Den felles justeringskurven drar du med fingeren. Den gjelder begge ørene og legges oppå testresultatet. Finjustering per øre ligger under «Vis finjustering per øre».
4. **Musikk**: slå på «Bruk lydprofilen på musikk». Låter det tynt eller skarpt, skru ned «Styrke» eller opp «Bass».
   - «Alle apper» er standard. Velg «Bare apper som melder fra» bare hvis du ikke hører noen forskjell. Da må avspillingen startes på nytt.
5. **Mikrofonlytting**: velg mikrofonen på ledningen eller i telefonen, og trykk «Start mikrofonlytting». Den fortsetter med skjermen av, og du kan stoppe den fra varselet.

## Å vite om

- **Testen er ikke en audiograf-test.** Den måler med dine egne EarPods i øret. Det er faktisk en fordel her, fordi profilen da også retter opp EarPods sin egen klang. Bytter du ørepropper, bør du ta testen på nytt.
- **Forsterkningen følger halv-gevinst-regelen.** Appen forsterker med halvparten av hvor mye svakere du hører en frekvens enn din beste frekvens. Det er en vanlig startregel for høreapparater, og du kan skru opp eller ned med «Styrke».
- **Lyden blir litt forsinket ved mikrofonlytting.** På Pixel 6 er forsinkelsen typisk 20–40 ms. Siden EarPods sitter løst, hører du også den direkte lyden. Din egen stemme kan derfor låte litt hul.
- **Om «all lyd» virker, avhenger av Android-versjonen.** Metoden for å påvirke all lyd er offisielt foreldet, men virker fortsatt på mange telefoner. Det er derfor reservemetoden finnes.
- **Dette er ikke et medisinsk høreapparat.** Merker du at hørselen svekkes, er en ordentlig hørselstest via fastlegen lurt.

## Bruke profilen på PC-en (Windows)

1. Installer gratisprogrammet **Equalizer APO** (sourceforge.net/projects/equalizerapo). Under installasjonen huker du av for lydenheten PC-en spiller gjennom, for eksempel TV-en via HDMI eller DisplayPort. Start PC-en på nytt.
2. I appen trykker du **Send til PC** under «Bruk profilen på PC-en», og sender teksten til deg selv.
3. Åpne `C:\Program Files\EqualizerAPO\config\config.txt` i Notisblokk som administrator. Erstatt innholdet med teksten, og lagre. Endringen virker med en gang.
4. Blokk A (samme kurve på begge kanaler) er på fra start, og den passer for høyttalere. For hodetelefoner bytter du til blokk B slik det står i teksten.
