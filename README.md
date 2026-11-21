# GTA Live Prekladač (Android Kotlin)

Natívna Android aplikácia pre živý preklad herných dialógov (EN -> SK / CZ) pri hraní z televízora.

## Funkcie
- **AI Sluch (M/Ž):** Natívne nahrávanie zvuku s VAD detekciou hlasu a odosielaním audio úryvkov priamo do Gemini (`gemini-3.5-flash-lite`), ktoré počuje reálny hlas postavy, filtruje streľbu/hudbu z hry a správne skloňuje mužský a ženský rod.
- **Rýchly STT režim:** Alternatívny režim cez systémový Android SpeechRecognizer s okamžitým náhľadom textu.
- **OLED dizajn & Wake Lock:** Čierna téma šetriaca displej, mobil počas hrania nezhasne.
- **Slúchadlá (TTS):** Predčítavanie prekladu v slovenčine alebo češtine s odlíšenou výškou hlasu pre muža a ženu.

## Zostavenie APK cez GitHub Actions
1. Nahraj všetky súbory do GitHub repozitára (vrátane `.github/workflows/build-apk.yml`).
2. Otvor kartu **Actions** -> **Build Android APK** -> klikni **Run workflow** (alebo sa spustí automaticky po nahratí).
3. Po ~2 minútach si stiahni hotový balíček `GTA-Live-Prekladac-APK` (obsahuje `app-debug.apk`).
