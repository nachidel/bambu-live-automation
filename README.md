# bambu-live-automation

Automatisation du lancement et de l'arrêt d'un live YouTube à partir des événements d'impression d'une Bambu Lab H2C.

Le programme surveille la H2C via Bambu Cloud, prépare le PC studio et OBS, démarre le flux vidéo de la caméra sans avoir besoin d'ouvrir l'interface Bambu Studio, crée un broadcast YouTube, lance la diffusion et l'arrête à la fin de l'impression.

> **État actuel** : la chaîne H2C → caméra → OBS → YouTube a été validée en réel.
>
> **Limitation connue** : le fichier `url.txt` utilisé par `bambu_source.exe` peut devenir invalide ou nécessiter un rafraîchissement. Le contournement actuellement validé consiste à lancer Bambu Studio, ouvrir la caméra, fermer Bambu Studio puis relancer l'automatisation. Le rafraîchissement autonome de cette session reste à implémenter.

---

## Architecture

```text
Bambu H2C
    │
    │ Bambu Cloud
    ▼
bambu-live-automation
    │
    ├── PrintAutomationController
    │
    ├── contrôle PC / Wake-on-LAN
    │
    ├── attente OBS WebSocket
    │
    ├── BambuCameraService
    │      │
    │      ├── bambu_source.exe
    │      │        │ H264 brut
    │      │        ▼
    │      └── ffmpeg.exe
    │               │ RTP
    │               ▼
    │          127.0.0.1:1234
    │               │
    │               ▼
    │              OBS
    │
    └── LiveStreamingService
           │
           ├── stream YouTube réutilisable
           ├── broadcast YouTube
           ├── configuration OBS RTMPS
           ├── StartStream
           ├── attente ingestion
           ├── transition LIVE
           └── transition COMPLETE
```

La partie vidéo est donc :

```text
H2C -> bambu_source.exe -> H264 stdout -> ffmpeg.exe -> RTP -> OBS
```

Puis :

```text
OBS -> RTMPS -> YouTube
```

---

# Fonctionnement

## Démarrage d'une impression

Sur `PREPARING` ou `PRINTING` :

1. le contrôleur demande le démarrage de l'automatisation ;
2. le programme évite les démarrages en double ;
3. il vérifie si le PC studio est joignable ;
4. si le PC est éteint et le WOL activé, il envoie un magic packet ;
5. il attend que Windows soit réellement joignable ;
6. il attend OBS WebSocket ;
7. il démarre le bridge caméra H2C ;
8. il prépare le stream YouTube réutilisable ;
9. il configure OBS avec l'adresse RTMPS et la clé du stream ;
10. il crée un broadcast YouTube ;
11. il associe le broadcast au stream ;
12. il démarre OBS ;
13. il attend que le stream OBS soit réellement actif ;
14. il attend que YouTube indique que l'ingestion est `active` ;
15. il demande la transition du broadcast vers `live` ;
16. il attend le passage `liveStarting -> live`.

Le programme privilégie les états réels à des temporisations fixes.

## Démarrage du programme pendant une impression

Le programme sait reprendre une impression déjà en cours.

Si la H2C est déjà en `PRINTING` au moment de la connexion Bambu Cloud, la réconciliation d'état déclenche le workflow de live.

Ce scénario a été validé sur une vraie impression.

## Pause

Une pause ne coupe rien :

```text
PAUSED
→ caméra conservée
→ OBS conservé
→ live YouTube conservé
```

La reprise ne recrée pas un live.

## Fin ou erreur

Sur `FINISHED` ou `FAILED` :

```text
annulation éventuelle du démarrage en cours
→ YouTube COMPLETE
→ arrêt OBS
→ fermeture OBS WebSocket
→ arrêt caméra
```

Le broadcast est terminé avant l'arrêt de la vidéo.

---

# Protection du stream OBS

Le programme ne doit pas arrêter un stream lancé manuellement.

Au démarrage, si OBS est déjà en train de streamer :

```text
OBS déjà actif
→ l'automatisation refuse de prendre possession du stream
→ elle ne l'arrêtera pas
```

Quand l'automatisation demande elle-même `StartStream`, elle conserve l'information permettant de savoir que ce stream lui appartient.

La clé de stream YouTube ne doit jamais apparaître dans les logs.

---

# Caméra H2C

## Principe

Les outils caméra utilisés sont ceux distribués avec Bambu Studio :

```text
bambu_source.exe
ffmpeg.exe
ffmpeg.cfg
ffmpeg.sdp
url.txt
```

Le `ffmpeg.cfg` observé correspond à :

```text
-fflags
nobuffer
-flags
low_delay
-analyzeduration
10
-probesize
3200
-f
h264
-i
pipe:
-vcodec
copy
-f
rtp
rtp://127.0.0.1:1234
```

`bambu_source.exe` écrit donc du H264 brut sur stdout. FFmpeg le lit par `pipe:` et le renvoie sans réencodage vers OBS en RTP.

## Fichier SDP

OBS lit un SDP correspondant à :

```text
m=video 1234 RTP/AVP 96
a=rtpmap:96 H264/90000
```

Le fichier actuellement utilisé peut typiquement se trouver ici :

```text
%APPDATA%\BambuStudioBeta\cameratools\ffmpeg.sdp
```

ou :

```text
%APPDATA%\BambuStudio\cameratools\ffmpeg.sdp
```

## `url.txt`

`bambu_source.exe` est appelé sous cette forme :

```text
bambu_source.exe bambu:///camera/C:/.../cameratools/url.txt
```

Le fichier contient une URL interne Bambu de type :

```text
bambu:///tutk?uid=...&authkey=...&passwd=...&region=...&device=...&...
```

Il contient des informations sensibles.

**Ne jamais commiter `url.txt`.**

## Limitation actuelle de la session caméra

Un comportement réel a été observé :

```text
bridge autonome OK
→ perte du flux
→ redémarrage du bridge KO
→ lancement de Bambu Studio
→ caméra Bambu Studio active
→ fermeture de Bambu Studio
→ redémarrage du bridge OK
```

Cela indique que Bambu Studio rafraîchit probablement une information nécessaire à la session caméra.

Contournement actuel :

1. lancer Bambu Studio ;
2. ouvrir la caméra H2C ;
3. attendre l'image ;
4. fermer Bambu Studio ;
5. relancer l'automatisation.

L'objectif futur est de reproduire ce rafraîchissement sans Bambu Studio.

---

# Recherche des outils caméra

`BambuCameraService` cherche les outils dans cet ordre :

```text
BAMBU_CAMERA_TOOLS_DIR
%APPDATA%\BambuStudioBeta\cameratools
%APPDATA%\BambuStudio\cameratools
%LOCALAPPDATA%\bambu-live-automation\camera-tools
```

Il recherche au minimum :

```text
bambu_source.exe
ffmpeg.exe
```

Si les outils ne sont pas présents, le service peut utiliser les manifests Bambu locaux `network_plugins.json` pour retrouver le package Windows.

En fallback, une URL officielle peut être fournie avec :

```text
BAMBU_CAMERA_PLUGIN_URL
```

Le téléchargement des exécutables ne crée pas automatiquement un `url.txt` valide.

---

# YouTube

## Stream réutilisable

Le projet utilise un stream réutilisable nommé :

```text
bambu-live-automation
```

S'il n'existe pas, il est créé.

La clé de stream retournée par l'API n'est jamais loggée.

## Broadcast par impression

Chaque impression crée un nouveau broadcast.

Titre actuel :

```text
Impression 3D - <nom de l'impression Bambu>
```

Description actuelle :

```text
Diffusion automatique de l'impression 3D
```

Visibilité :

```text
YOUTUBE_PRIVACY=private
YOUTUBE_PRIVACY=unlisted
YOUTUBE_PRIVACY=public
```

## Cycle de vie

Le programme pilote explicitement le live :

```text
create broadcast
→ bind reusable stream
→ start OBS
→ wait OBS active
→ wait YouTube ingestion active
→ transition live
→ wait liveStarting -> live
→ diffusion
→ transition complete
→ stop OBS
```

`AutoStart` et `AutoStop` ne sont pas utilisés pour le workflow principal.

---

# OAuth Google / YouTube

Fichier OAuth par défaut :

```text
config/client_secret.json
```

Token OAuth par défaut :

```text
youtube-token/token.json
```

Ces deux emplacements doivent être ignorés par Git.

Le premier lancement OAuth demande l'autorisation Google puis enregistre le token localement.

Le mode prévu pour vérifier l'authentification est :

```text
YOUTUBE_AUTH_TEST=true
```

---

# Bambu Cloud

Le programme utilise la bibliothèque :

```text
com.nachidel:bambu-cloud-kotlin:0.1.0
```

Le token Bambu est fourni avec :

```text
BAMBU_TOKEN
```

Le suivi de l'impression reste basé sur Bambu Cloud. Il n'est pas nécessaire de passer la H2C en mode LAN pour recevoir les états d'impression.

---

# Wake-on-LAN

Si :

```text
WOL_ENABLED=true
```

et que le PC studio est hors ligne :

```text
magic packet
→ attente Windows
→ attente OBS
→ caméra
→ YouTube
```

Le WOL doit être activé dans le BIOS/UEFI, la carte réseau et Windows.

Pour une automatisation complète, OBS doit également démarrer automatiquement avec Windows.

---

# Variables d'environnement

## Bambu

| Variable | Défaut | Obligatoire | Description |
|---|---|---:|---|
| `BAMBU_TOKEN` | aucun | oui en réel | Token Bambu Cloud. |
| `BAMBU_SIMULATION` | `false` | non | Utilise le simulateur au lieu de Bambu Cloud. |

## Caméra

| Variable | Défaut | Obligatoire | Description |
|---|---|---:|---|
| `BAMBU_CAMERA_ENABLED` | `true` sous Windows, sinon `false` | non | Active le bridge caméra. |
| `BAMBU_CAMERA_TOOLS_DIR` | recherche automatique | non | Dossier de `bambu_source.exe` et `ffmpeg.exe`. |
| `BAMBU_CAMERA_URL_FILE` | recherche automatique | non | Chemin explicite de `url.txt`. |
| `BAMBU_CAMERA_RTP_URL` | `rtp://127.0.0.1:1234` | non | Destination RTP FFmpeg. |
| `BAMBU_CAMERA_PLUGIN_URL` | aucun | non | URL officielle Bambu utilisée si les outils sont absents. |

## PC studio / WOL

| Variable | Défaut | Obligatoire | Description |
|---|---|---:|---|
| `STUDIO_PC_IP` | `192.168.1.10` | non | IP du PC studio. |
| `WOL_ENABLED` | `false` | non | Active le Wake-on-LAN. |
| `STUDIO_PC_MAC` | aucun | si WOL | MAC du PC studio. |
| `WOL_BROADCAST` | aucun | si WOL | Broadcast LAN. |
| `WOL_PORT` | `7` | non | Port UDP du magic packet. |

## OBS

| Variable | Défaut | Obligatoire | Description |
|---|---|---:|---|
| `OBS_HOST` | `STUDIO_PC_IP` | non | Hôte OBS WebSocket. |
| `OBS_PORT` | `4455` | non | Port obs-websocket. |
| `OBS_PASSWORD` | aucun | si auth activée | Mot de passe OBS WebSocket. |
| `OBS_STREAM_ENABLED` | `false` | non | Autorise le vrai démarrage du live. |

## YouTube

| Variable | Défaut | Obligatoire | Description |
|---|---|---:|---|
| `YOUTUBE_CLIENT_SECRET` | `config/client_secret.json` | non | JSON OAuth Desktop. |
| `YOUTUBE_TOKEN_FILE` | `youtube-token/token.json` | non | Stockage du token OAuth. |
| `YOUTUBE_PRIVACY` | `private` | non | `private`, `unlisted` ou `public`. |

## Tests

| Variable | Défaut | Description |
|---|---|---|
| `YOUTUBE_AUTH_TEST` | `false` | Teste OAuth et la chaîne YouTube. |
| `YOUTUBE_STREAM_SETUP` | `false` | Vérifie/crée le stream réutilisable. |
| `YOUTUBE_OBS_SETUP` | `false` | Configure OBS sans démarrer le stream. |
| `YOUTUBE_BROADCAST_TEST` | `false` | Crée un broadcast privé de test. |
| `YOUTUBE_LIVE_TEST` | `false` | Lance un vrai live de test, forcé en privé. |

Une seule variable de test YouTube doit être active à la fois.

---

# Exemple IntelliJ

Exemple sans secrets réels :

```xml
<map>
  <entry key="BAMBU_SIMULATION" value="false" />

  <entry key="BAMBU_CAMERA_ENABLED" value="true" />
  <entry key="BAMBU_CAMERA_TOOLS_DIR" value="C:\Users\USER\AppData\Roaming\BambuStudioBeta\cameratools" />
  <entry key="BAMBU_CAMERA_URL_FILE" value="C:\Users\USER\AppData\Roaming\BambuStudioBeta\cameratools\url.txt" />
  <entry key="BAMBU_CAMERA_RTP_URL" value="rtp://127.0.0.1:1234" />

  <entry key="STUDIO_PC_IP" value="192.168.1.10" />

  <entry key="WOL_ENABLED" value="false" />
  <entry key="STUDIO_PC_MAC" value="XX-XX-XX-XX-XX-XX" />
  <entry key="WOL_BROADCAST" value="192.168.1.255" />
  <entry key="WOL_PORT" value="7" />

  <entry key="OBS_HOST" value="192.168.1.10" />
  <entry key="OBS_PORT" value="4455" />
  <entry key="OBS_PASSWORD" value="A_DEFINIR" />
  <entry key="OBS_STREAM_ENABLED" value="true" />

  <entry key="YOUTUBE_PRIVACY" value="public" />
  <entry key="YOUTUBE_AUTH_TEST" value="false" />
  <entry key="YOUTUBE_STREAM_SETUP" value="false" />
  <entry key="YOUTUBE_OBS_SETUP" value="false" />
  <entry key="YOUTUBE_BROADCAST_TEST" value="false" />
  <entry key="YOUTUBE_LIVE_TEST" value="false" />
</map>
```

`BAMBU_TOKEN` et les credentials OAuth doivent être gérés comme secrets locaux.

---

# Compilation et lancement

## Prérequis

- JDK 21 ;
- Gradle Wrapper ;
- accès GitHub Packages pour `bambu-cloud-kotlin` ;
- OBS Studio ;
- obs-websocket ;
- compte Google / YouTube ;
- sous Windows : outils caméra Bambu.

Build Windows :

```powershell
.\gradlew.bat clean build
```

Lancement :

```powershell
.\gradlew.bat run
```

---

# GitHub Packages

Le build récupère :

```text
com.nachidel:bambu-cloud-kotlin:0.1.0
```

Dans :

```text
~/.gradle/gradle.properties
```

configurer par exemple :

```properties
gpr.user=VOTRE_UTILISATEUR_GITHUB
gpr.key=VOTRE_TOKEN_GITHUB
```

Ne jamais commiter ce fichier ni son token.

---

# OBS

OBS WebSocket doit être activé.

Configuration typique :

```text
port : 4455
authentification : activée
mot de passe : OBS_PASSWORD
```

La scène OBS doit contenir la source média lisant `ffmpeg.sdp`.

Le programme configure automatiquement la destination de streaming OBS avec le serveur RTMPS et la clé YouTube.

---

# Configuration YouTube initiale

1. créer un projet Google Cloud ;
2. activer YouTube Data API v3 ;
3. créer un client OAuth Desktop ;
4. enregistrer le JSON dans `config/client_secret.json` ;
5. mettre `YOUTUBE_AUTH_TEST=true` ;
6. lancer l'application ;
7. terminer l'autorisation dans le navigateur ;
8. vérifier la création de `youtube-token/token.json` ;
9. remettre `YOUTUBE_AUTH_TEST=false`.

---

# H2C OBS overlay - thumbnail + dual head temperatures

## Correction importante

Les deux températures de tête sont récupérées dans :

`print.device.extruder.info[]`

avec les entrées `id = 0` et `id = 1`.

Le champ `temp` est compacté sur 32 bits :

- 16 bits bas : température actuelle
- 16 bits hauts : température cible

Exemples vérifiés dans les captures MQTT H2C :

- `14418140 = 0x00DC00DC` -> `220 / 220 °C`
- `9175232  = 0x008C00C0` -> `192 / 140 °C`
- `11796667 = 0x00B400BB` -> `187 / 180 °C`

Les champs racine `nozzle_temper` et `nozzle_target_temper` sont conservés,
mais le code ne suppose plus qu'ils correspondent systématiquement à la tête active.

L'overlay affiche donc explicitement :

- Tête 0
- Tête 1
- Plateau

sans inventer pour l'instant une correspondance gauche/droite.

## OBS

URL :

`http://<IP_DU_RASPBERRY>:8080/obs`

Taille conseillée :

`1080 x 280`


# Simulateur

Activer :

```text
BAMBU_SIMULATION=true
```

Commandes :

```text
p  préparer
s  démarrer
a  pause
r  reprendre
f  terminer
x  échouer
i  démarrage alors qu'une impression est déjà active
q  quitter
```

Scénario typique :

```text
p
a
r
f
```

Résultat attendu :

```text
p -> live démarre
a -> live reste actif
r -> live reste actif
f -> YouTube COMPLETE + OBS stop + caméra stop
```

---

# Script caméra manuel

`Demarrer_Camera_H2C.cmd` permet de tester la caméra indépendamment du reste du programme.

Il reproduit :

```text
bambu_source.exe | ffmpeg.exe
```

vers :

```text
rtp://127.0.0.1:1234
```

Il est utile pour distinguer un problème caméra d'un problème OBS ou YouTube.

---

# Logs caméra

Emplacements :

```text
%LOCALAPPDATA%\bambu-live-automation\logs\bambu-source.log
%LOCALAPPDATA%\bambu-live-automation\logs\ffmpeg-camera.log
```

PowerShell :

```powershell
Get-Content "$env:LOCALAPPDATA\bambu-live-automation\logs\bambu-source.log" -Tail 100
```

```powershell
Get-Content "$env:LOCALAPPDATA\bambu-live-automation\logs\ffmpeg-camera.log" -Tail 100
```

Processus :

```powershell
Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -in @("bambu_source.exe", "ffmpeg.exe")
    } |
    Select-Object ProcessId, ParentProcessId, Name, ExecutablePath, CommandLine
```

---

# Dépannage

## Live toujours privé

Vérifier :

```text
YOUTUBE_PRIVACY=public
```

`YOUTUBE_LIVE_TEST=true` reste volontairement privé.

## OBS est déjà en train de streamer

L'automatisation refuse volontairement de prendre le contrôle du stream existant.

## Aucune image H2C

Vérifier :

```text
BAMBU_CAMERA_ENABLED=true
```

puis :

- `bambu_source.exe` ;
- `ffmpeg.exe` ;
- `url.txt` ;
- `ffmpeg.sdp` dans OBS ;
- port RTP `1234` ;
- logs caméra.

Si le bridge fonctionnait auparavant mais ne redémarre plus, appliquer temporairement le rafraîchissement via Bambu Studio décrit plus haut.

## `bambu_source.exe` introuvable

Définir :

```text
BAMBU_CAMERA_TOOLS_DIR=C:\...\cameratools
```

ou laisser le téléchargement automatique tenter de récupérer le package Bambu.

## `url.txt` introuvable

Définir :

```text
BAMBU_CAMERA_URL_FILE=C:\...\url.txt
```

Le téléchargement des outils ne suffit pas à créer ce fichier.

## YouTube reste en `liveStarting`

Le programme continue de sonder l'état jusqu'à `live`. Une transition de plusieurs secondes a été observée en réel.

---

# Arborescence

```text
src/main/kotlin/com/nachidel/bambu/live/
│
├── Main.kt
├── automation/
│   └── PrintAutomationController.kt
├── bambu/
│   └── BambuPrinterService.kt
├── camera/
│   └── BambuCameraService.kt
├── live/
│   └── LiveStreamingService.kt
├── obs/
│   ├── ObsMonitor.kt
│   └── ObsWebSocketClient.kt
├── simulator/
│   └── BambuEventSimulator.kt
├── studio/
│   ├── StudioPcMonitor.kt
│   └── WakeOnLanService.kt
└── youtube/
    ├── YouTubeOAuthClient.kt
    └── YouTubeService.kt
```

## Rôles

### `Main.kt`

Configuration, modes de test et orchestration générale.

### `PrintAutomationController`

Transforme les événements Bambu en actions et évite les actions dupliquées.

### `BambuPrinterService`

Connexion Bambu Cloud et maintien du snapshot courant.

### `BambuCameraService`

Recherche les outils, démarre `bambu_source` + FFmpeg et publie le RTP.

### `ObsMonitor`

Attend que le port OBS WebSocket soit joignable.

### `ObsWebSocketClient`

Authentification obs-websocket, état du stream, Start/Stop, configuration de la destination.

### `YouTubeOAuthClient`

OAuth Google, stockage et renouvellement des tokens.

### `YouTubeService`

Streams réutilisables, broadcasts, bind et transitions YouTube.

### `LiveStreamingService`

Orchestration OBS/YouTube, gestion de propriété et nettoyage.

---

# `.gitignore` recommandé

```gitignore
.idea/
*.iml
out/

.gradle/
.kotlin/
build/

.env
.env.*
local.properties

config/
youtube-token/
```

Ne jamais commiter :

```text
config/client_secret.json
youtube-token/token.json
url.txt
BAMBU_TOKEN
OBS_PASSWORD
clé YouTube
PAT GitHub
```

---

# État validé

Validé en réel ou par simulation :

- connexion Bambu Cloud ;
- impression déjà en cours lors du lancement ;
- `PREPARING` / `PRINTING` ;
- pause et reprise sans interruption du live ;
- fin d'impression ;
- OBS WebSocket ;
- configuration RTMPS ;
- stream YouTube réutilisable ;
- création et association du broadcast ;
- ingestion YouTube ;
- `liveStarting -> live` ;
- `live -> complete` ;
- arrêt OBS ;
- caméra H2C via `bambu_source.exe` sans interface Bambu Studio ;
- H264 brut -> FFmpeg ;
- RTP -> OBS ;
- visibilité YouTube configurable ;
- protection d'un stream OBS déjà existant.

---

# À faire

## Rafraîchissement autonome de `url.txt`

Priorité actuelle : supprimer le dernier besoin ponctuel de Bambu Studio.

## Surveillance caméra

Une fois le mécanisme de session mieux compris, ajouter une reconnexion caméra sans tuer le live YouTube.

## Raspberry Pi

Architecture cible :

```text
Raspberry Pi
    │
    ├── Bambu Cloud
    ├── WOL
    └── orchestration distante
           │
           ▼
PC Windows
    ├── bridge caméra
    └── OBS
```

## Arrêt automatique du PC

À ajouter avec protections :

- activité utilisateur ;
- stream externe ;
- présence ;
- délai de grâce.

---

# Résumé

```text
H2C
→ Bambu Cloud
→ PC studio
→ caméra H2C
→ OBS
→ YouTube LIVE
→ FINISHED / FAILED
→ YouTube COMPLETE
→ OBS stop
→ caméra stop
```

Le seul point encore non totalement autonome est le rafraîchissement de la session caméra utilisée par `url.txt`.
