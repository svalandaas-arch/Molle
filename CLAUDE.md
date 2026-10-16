# Mølle – notater for Claude

Eier: Sigurd (GitHub: svalandaas-arch). Snakk norsk (bokmål), enkelt og uten teknisk sjargong. Eieren jobber mest fra en Android-telefon og er ikke utvikler: gjør jobben selv i repoet i stedet for å be ham gjøre tekniske steg.

## Hva appen er

Intervallapp for tredemølla. Man setter opp økta på forhånd (oppvarming, steg med drag og pauser, serier med seriepause, nedjogg, fart og stigning for hver del) og ser distanse, tid, fart, stigning og puls mens man løper. Distansen regnes ut fra farten man har stilt inn. Økta kan lagres som .fit (tidligere .tcx) og lastes opp til Strava/Garmin. Med «Fiktiv rute» får filen en rundløype på Juvatn i Åseral og sub_sport virtual_activity, så Strava viser høydemeter og merker økta som virtuell løpetur.

## Hvor ting ligger

- `docs/` publiseres med GitHub Pages på https://svalandaas-arch.github.io/Molle/
  - `docs/index.html` – startside: velg Android eller iPhone/iPad
  - `docs/android/` – nedlastingsside for APK-en
  - `docs/iphone/` – forklarer «Legg til på Hjem-skjerm» i Safari
  - `docs/app/` – selve nettappen (PWA med service worker `sw.js`)
- `app/` – Android-appen (Kotlin, en WebView rundt nettappen)
  - `app/src/main/assets/www/index.html` – nettappen med kobling til Android (`window.MolleNative`)
  - `BleHub.kt` – Bluetooth: Zwift-fotpod (Running Speed and Cadence, 0x1814) og pulsmåler (0x180D)
  - `TreadmillService.kt` – bakgrunnstjeneste med status i varslingsfeltet
  - `MainActivity.kt` – WebView, tillatelser, oppdateringssjekk mot `versjon.json`
- `.github/workflows/bygg-apk.yml` – bygger APK ved hver push til main og legger `molle.apk` og `versjon.json` ut i releasen `siste`. Versjonsnummeret er `GITHUB_RUN_NUMBER`.
- Fast nedlasting: https://github.com/svalandaas-arch/Molle/releases/download/siste/molle.apk

## Viktig ved endringer

- Nettappen finnes i to kopier: `docs/app/index.html` og `app/src/main/assets/www/index.html`. Samme endring må inn i begge. Android-kopien har i tillegg kode for `MolleNative`.
- Bump `CACHE` i `docs/app/sw.js` når nettappen endres, ellers får brukerne gammel versjon.
- Signeringsnøkkelen `app/molle.keystore` må ikke byttes, ellers kan ikke nye versjoner installeres over de gamle.
- Data lagres i nettleseren (localStorage, nøkler `molle.*`). Ikke endre formatet uten å lese gamle data riktig.
- Sjekk at bygget på GitHub blir grønt etter push. Feil vises som merknader på bygget (check-run annotations).
- GitHub Pages-innstillinger og repo-innstillinger kan ikke endres via API herfra. Be eieren om det, med enkle steg for telefon.

## Ikke testet ennå

Zwift-fotpod, pulsmåler og bakgrunnskjøring i Android-appen er ikke testet på ekte telefon.
