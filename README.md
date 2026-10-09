# Mølle

Intervaller på tredemølla: still inn økta på forhånd, se distanse, tid, fart, stigning og puls mens du løper, og lagre økta som en fil til Strava og Garmin.

## Nettappen

**https://svalandaas-arch.github.io/molle/**

Åpne lenken i Chrome på Android og velg ⋮ → «Installer app». På iPhone og iPad: Safari → Del → «Legg til på Hjem-skjerm» (uten pulsmåler, siden Safari ikke støtter Bluetooth).

Koden ligger i `docs/`.

## Android-appen (med Zwift-fotpod)

Den ferdige appen ligger under **Releases → Siste versjon** som `molle.apk`. Last den ned på telefonen og installer. Første gang må du tillate installering fra nettleseren.

I tillegg til alt i nettappen:

- **Zwift-fotpod:** Telefonen sender farten din til Zwift over Bluetooth.
- **Pulsmåler** via Android sin egen Bluetooth.
- **Bakgrunn:** Appen kjører videre med skjermen av, med status i varslingsfeltet.
- **.tcx-filer** lagres rett i Nedlastinger.

Krever Android 10 eller nyere. Appen bygges automatisk av GitHub Actions hver gang koden endres.

### Slik bruker du den med Zwift

1. Åpne Mølle og trykk **Slå på** ved Zwift-fotpod.
2. I Zwift (PC, Mac, Apple TV eller nettbrett): gå til parringsskjermen, velg **Run Speed** og velg navnet på telefonen.
3. Start økta i Mølle. Zwift får farten du har i appen, så hold den lik mølla.

Kadens anslås ut fra farten. Spør Zwift om å kalibrere fotpoden, setter du den til 100 % eller hopper over. Zwift må kjøre på en annen enhet enn telefonen.

## Oppbygging

- `docs/` – nettappen (publiseres med GitHub Pages)
- `app/src/main/assets/www/index.html` – samme app med kobling til Android
- `app/src/main/java/…` – Android-delen: Bluetooth (fotpod og puls), bakgrunnstjeneste, lagring av filer

`app/molle.keystore` signerer appen slik at nye versjoner kan installeres over de gamle. Bytt nøkkel før en eventuell publisering i Google Play.
