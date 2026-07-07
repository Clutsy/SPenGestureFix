# S Pen Gesture Fix — Note 3 (SM-N9005)

App Android root per ripristinare e **superare** quello che offriva Air
Command sul Note 3, su ROM AOSP/LineageOS dove il framework S Pen
proprietario Samsung non c'è. Nessun permesso Internet: gira solo in
locale sul telefono.

## Cosa fa

**Tasto laterale S Pen — completamente riprogrammabile.** Tre gesti
(clic singolo, doppio clic, pressione lunga), ognuno assegnabile a
qualsiasi azione dalla lista sotto. Di default: clic = screenshot,
doppio clic = apri la ruota, pressione lunga = Screen Write.

**Ruota S Pen** — il menu radiale stile Air Command, con sfondo
personalizzabile con una foto a tua scelta (presa dalla galleria,
persistente anche dopo un riavvio) e 6 spicchi configurabili
indipendentemente.

**Azioni disponibili** (per il tasto o per uno spicchio della ruota):

| Azione | Cosa fa | Equivalente Air Command originale |
|---|---|---|
| Apri app | Lancia un'app a scelta | — |
| Apri ruota S Pen | Apre/chiude il menu radiale | Air Command |
| Screenshot | Cattura lo schermo | — |
| Screen Write | Screenshot + disegno libero con colori/annulla | Screen Write |
| Ritaglia area schermo | Screenshot + selezione rettangolare da ritagliare | Scrapbook / Image Clip (semplificato: solo rettangolo) |
| Nota rapida | Popup di testo, rileva numeri di telefono (tasto "Chiama"), scorciatoia Maps | Action Memo (semplificato: testo, non riconoscimento scrittura) |
| Cerca e apri app | Ricerca con filtro live tra le app installate | S Finder (semplificato: solo app, non tutto il device) |
| Finestra fluttuante | Apre un'app scelta in una mini finestra ridimensionabile | Pen Window |
| Torcia | Accende/spegne il flash | — (nuova) |
| Wi-Fi on/off | Da root, via `svc wifi` | — (nuova) |
| Bluetooth on/off | Da root, via `svc bluetooth` | — (nuova) |
| Silenzia/riattiva volume | Simula il tasto mute | — (nuova) |
| Blocca schermo | Spegne lo schermo (se hai un blocco impostato, lo blocca) | — (nuova) |
| Comando root personalizzato | Qualsiasi comando shell tu voglia, eseguito come root | — (nuova, per estendere senza scrivere codice) |

Nessuna azione fa handwriting recognition o riconoscimento di
contenuto: dove l'originale usava l'IA di Samsung per capire cosa hai
scritto o disegnato, qui uso euristiche semplici (regex per i numeri
di telefono) o semplicemente lascio scegliere a te (ritaglio manuale
invece di rilevamento automatico del contenuto).

## Stato dei test (06/07/2026)

- `su -c "getevent -l /dev/input/event3"` lanciato a mano: **funziona**,
  root e digitizer sono a posto.
- Dai dati raccolti: l'hover ora usa `BTN_DIGI`/`BTN_TOUCH` invece di
  una soglia indovinata su `ABS_DISTANCE` — zero calibrazione
  necessaria (vedi `PenGestureAnalyzer.kt` per i dettagli).
- Resta sperimentale/da verificare solo il rilevamento estrazione
  penna dall'alloggiamento (device "w1", vedi sezione dedicata sotto).

## Architettura del codice

- `EventDeviceFinder` + `EPenInputReader` — trovano e leggono in
  streaming i device di input via `getevent -l` root (invariati dalla
  prima versione).
- `PenGestureAnalyzer` — riconosce clic/doppio clic/pressione
  lunga/hover dagli eventi grezzi.
- `ActionType` + `PenAction` — il catalogo di tutte le azioni possibili
  e il modello dati di un'azione concreta.
- `GestureBindings` — quale `PenAction` è assegnata a ciascuno dei 3
  gesti del tasto (persistita in SharedPreferences).
- `WheelConfig` — gli spicchi della ruota e l'immagine di sfondo.
- `ActionExecutor` — **l'unico punto** in cui ogni `ActionType` diventa
  un effetto reale. Per aggiungere una funzione nuova: una voce in
  `ActionType`, un branch qui, fatto — tutto il resto (UI, persistenza,
  binding) funziona già.
- `ActionPickerDialog` + `AppPicker` — la UI di scelta a due passi
  (tipo di azione, poi app/testo se serve), con ricerca live sulle app.
- `WheelOverlay` — disegna e gestisce il menu radiale (canvas custom,
  non un componente di sistema).
- `ScreenWriteActivity` / `SmartSelectActivity` / `QuickNoteActivity` /
  `NotesListActivity` — le funzioni "pesanti" che servono una loro
  schermata.
- `SPenGestureService` — orchestratore: legge gli eventi, li passa
  all'analyzer, esegue il binding giusto tramite `ActionExecutor`.

## Ruota: come funziona lo sfondo con la foto

In MainActivity scegli l'immagine con il selettore di sistema
(`ACTION_OPEN_DOCUMENT`), e l'app chiede un permesso persistente su
quell'URI (`takePersistableUriPermission`) così l'immagine resta
leggibile anche dopo un riavvio del telefono, senza bisogno del
permesso di lettura storage classico.

## Parte sperimentale: rilevamento estrazione/inserimento penna

Il kernel Linux moderno ha un codice standard per questo
(`SW_PEN_INSERTED`, 0x0f), introdotto dopo il 2013: la ROM del Note 3
quasi certamente usa un codice proprietario precedente. Dal tuo
`getevent -lp` risulta un device "w1" con solo `SW 001a` — la mia
ipotesi più probabile, non confermata. Verifica con:

```
adb logcat -s SPenDebug
```

estraendo/inserendo la penna. Se cambia tra `00000000` e `00000001`
esattamente in quel momento, funziona (e se la polarità risulta
invertita, scambia i due branch in
`SPenGestureService.handlePenRemovedSwitch()`).

## Pen Window: nota importante

La "finestra fluttuante" usa un trucco reale ma sperimentale: abilita
le finestre freeform di Android (`settings put global
enable_freeform_support 1` + `force_resizable_activities 1`, fatto
automaticamente al primo utilizzo) e poi lancia l'app scelta con
`am start --windowingMode 5`. **Serve quasi certamente un riavvio del
telefono dopo il primo utilizzo** perché i flag vengano applicati del
tutto, e il risultato dipende da quanto la tua ROM supporta le finestre
freeform lato SystemUI (i controlli per ridimensionare/spostare la
finestra sono disegnati dalla ROM, non da questa app).

## Compatibilità con l'architettura del Note 3 (e altri Note)

Il progetto è **100% Kotlin/Java, zero codice nativo (NDK/JNI)**: non
esiste quindi nessun problema di ABI a 32/64 bit. Il Note 3 (SM-N9005,
Snapdragon 800/Krait 400, ARMv7 a 32 bit) esegue l'APK esattamente come
farebbe un dispositivo ARM64: bytecode Kotlin/Java compilato in DEX,
interpretato/JIT-compilato da ART, indipendente dall'architettura della
CPU. L'unica cosa davvero "specifica" del tuo dispositivo è il
**nome del device kernel e i suoi codici evento** (`sec_e-pen`,
`BTN_STYLUS`, `BTN_DIGI`, `BTN_TOUCH`), verificati sul tuo N9005 con i
tuoi stessi comandi `getevent`.

Su **altri Note** (Note 2, Note 4, Note Pro/Tab con S Pen dello stesso
periodo) è plausibile che funzioni allo stesso modo, perché condividono
la stessa famiglia di driver Wacom (`wacom_i2c`) — ma non è garantito:
nomi e codici potrebbero differire per generazione. Prima di fidarti,
ripeti sul tuo altro dispositivo gli stessi comandi diagnostici:

```
adb shell su -c "getevent -lp"
```

e controlla che compaia un device con nome simile a `sec_e-pen` con
gli stessi tasti (`BTN_STYLUS`, `BTN_DIGI`, `BTN_TOUCH`). Se il nome è
diverso, basta cambiare le costanti `DIGITIZER_DEVICE_NAME` in
`SPenGestureService.kt` — non serve altro. Su Note più recenti (8, 9,
10+) il problema che risolve questa app probabilmente non si pone
nemmeno: quei dispositivi hanno Treble/GSI e spesso mantengono più
supporto vendor anche su ROM custom.

## Limiti conosciuti

- **Richiede root continuo**: il servizio tiene sempre un processo
  root (`getevent -l`) attivo in background per leggere gli eventi.
- **Latenza da parsing testuale**: `getevent -l` invece di lettura
  binaria diretta costa qualche millisecondo per riga — bene per
  click/hover, non pensato per disegno/scrittura ad alta frequenza.
- **Screen Write / Ritaglio semplificati**: pennello singolo (nessuna
  sensibilità alla pressione), ritaglio solo rettangolare (non a forma
  libera come l'originale "Image Clip").
- **Nota rapida senza riconoscimento scrittura**: è testo digitato, il
  rilevamento numero di telefono è una semplice regex, non un motore
  NLP.
- **"Cerca e apri app" cerca solo tra le app installate**, non in
  contatti/impostazioni/file come il vero S Finder.
- **Pen Window è sperimentale e dipendente dalla ROM** (vedi sopra),
  serve un riavvio e non tutte le ROM disegnano bene i controlli della
  finestra freeform.
- **Rilevamento estrazione penna non confermato** (ipotesi sul device
  "w1", vedi sopra).
- **Wi-Fi/Bluetooth toggle**: lo stato è tracciato localmente
  dall'app, non letto dal sistema — se lo cambi da fuori (impostazioni,
  altra app), il toggle potrebbe risultare sfasato finché non lo riusi
  una volta.
- **Nessun vero Air View** (l'anteprima al passaggio del pennino dentro
  altre app): richiederebbe l'integrazione di ogni singola app, non è
  replicabile da un'app esterna.
  

## Come compilare

Apri la cartella in Android Studio (File > Open), lascia sincronizzare
Gradle (scarica le dipendenze la prima volta) e usa
Build > Build Bundle(s)/APK(s) > Build APK(s). Da riga di comando, con
Android SDK già configurato:

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Se aggiorni l'app dopo averla già installata:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(`-r` reinstalla mantenendo i dati salvati: gesti, spicchi, note,
sfondo della ruota restano.)

## Primo avvio

1. **Root** — al primo `su`, il gestore root (Magisk/SuperSU) chiederà
   conferma.
2. **Overlay** — pulsante "Concedi permesso overlay".
3. **Notifiche** (solo Android 13+) — richiesta automatica.
4. Configura i 3 gesti del tasto e gli spicchi della ruota (tocca una
   riga per cambiarla), eventualmente scegli una foto di sfondo.
5. Avvia il servizio.
