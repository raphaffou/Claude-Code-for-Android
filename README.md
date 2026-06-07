# Claude Android — Start Guide

Interface de chat native Android branchée sur Claude Code via Termux/PRoot. Tout tourne localement sur le téléphone — seul Anthropic voit le trafic.

---

## Architecture

```
[App Android]
  │
  ├── HTTP GET :8765/ping  → vérification serveur vivant
  ├── HTTP GET :8765/stop  → arrêt propre du serveur
  └── WebSocket :8766      → chat streaming bidirectionnel
                                    │
                           [server.py — asyncio — PRoot Ubuntu]
                                    │
                           subprocess: claude -p --output-format stream-json --verbose
                                    │
                           [Claude Code CLI — NVM — PRoot Ubuntu]
```

Le serveur Python parse les événements `stream-json` ligne par ligne et les relaie en temps réel via WebSocket : thinking, tool_use, tool_result, text_delta, done, error.

---

## Prérequis

### 1. Termux
Installer depuis **F-Droid** (pas le Play Store, version trop ancienne).

Puis dans Termux, donner l'accès au stockage Android — **à faire une seule fois, avant tout le reste** :
```bash
termux-setup-storage
```
→ Android demande une permission, l'accorder. Sans ça, Termux ne peut pas accéder à `/storage/emulated/0`.

### 2. PRoot Distro + Ubuntu
Dans Termux :
```bash
pkg install proot-distro
proot-distro install ubuntu
```

### 3. NVM + Node.js + Claude Code CLI (dans PRoot Ubuntu)
```bash
proot-distro login ubuntu
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc
nvm install 22
npm install -g @anthropic-ai/claude-code
# ⚠️  Noter la version exacte installée (ex: v22.22.3).
# Le fichier server.py contient ce numéro en dur — si la version diffère, mettre à jour CLAUDE_BIN dans server.py.
```
Se connecter à son compte Anthropic :
```bash
claude
# Claude affiche une URL et l'ouvre automatiquement dans le navigateur Android.
# Compléter le login dans le navigateur, puis revenir dans Termux.
exit
```

### 4. Python + websockets (dans PRoot Ubuntu)
```bash
proot-distro login ubuntu
apt update && apt install -y python3 python3-pip
pip install websockets
exit
```
> ⚠️ Si `pip install websockets` échoue avec une erreur de chemin, utiliser `pip install --target=/usr/local/lib/python3.XX/dist-packages websockets` en remplaçant `3.XX` par la version retournée par `python3 --version`.  
> Vérifier ensuite : `python3 -c "import websockets; print('ok')"`

### 5. Autoriser les commandes externes dans Termux
Dans Termux :
```bash
echo "allow-external-apps = true" >> ~/.termux/termux.properties
termux-reload-settings
```

---

## Installation de l'APK

> L'APK n'est pas distribué — il faut le builder depuis les sources (voir section **Rebuild de l'APK** ci-dessous) avant de pouvoir l'installer.

1. Copier `claude-messenger-core-debug.apk` sur le téléphone
2. L'installer (activer **"Sources inconnues"** si demandé)
3. Au **premier lancement**, Android affiche :
   > *"Autoriser Claude à exécuter des commandes dans Termux ?"*  
   → **Autoriser**

---

## Utilisation

### Démarrage
1. Ouvrir l'app **Claude**
2. L'app ping le serveur ; s'il est arrêté, elle le démarre automatiquement via Termux
3. L'icône serveur en haut à droite indique l'état :
   - Icône "off" — arrêté (tap pour démarrer)
   - Icône "on" — en marche (tap pour arrêter)

### Interface

| Zone | Description |
|---|---|
| **☰ (haut gauche)** | Ouvre le gestionnaire de sessions (drawer gauche) |
| **⚙️ (haut droite)** | Paramètres de sécurité de l'agent |
| **Icône serveur (haut droite)** | Toggle on/off du serveur Python |
| **Bulle "▶ Réflexion"** | Pensée interne de l'agent — tap pour déplier |
| **Pastille `⚡ Bash  …`** | Outil exécuté en temps réel (inline entre les réponses) |
| **● ● ●** | Indicateur "en train d'écrire" animé |

### Gestionnaire de sessions (drawer gauche)
- **Tap ☰** → ouvre la liste des sessions passées (titre auto-généré + date)
- **Tap `+`** → nouvelle session vierge
- **Tap sur une session** → bascule vers cette session (la courante est sauvegardée)
- Sessions persistées dans `.sessions.json`

### Paramètres de sécurité (⚙️)
Contrôle ce que Claude a le droit de faire :
- **Outils autorisés** : Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch, Task — toggle par outil
- **`--dangerously-skip-permissions`** : bypass de toutes les confirmations Claude
- **`--max-turns`** : nombre max de tours agent avant arrêt automatique

---

## Démarrage manuel du serveur

Si l'auto-démarrage ne fonctionne pas :
```bash
# Dans Termux :
proot-distro login ubuntu -- bash -c \
  "source /root/.nvm/nvm.sh && python3 /storage/emulated/0/claude_code/claude-android-server/server.py"
```

Ou en arrière-plan :
```bash
bash /storage/emulated/0/claude_code/claude-android-server/start_server.sh
```

Les logs du serveur sont dans `claude-android-server/server.log`.

---

## Fichiers importants

| Fichier | Rôle |
|---|---|
| `claude-android/app/build/outputs/apk/core/debug/claude-messenger-core-debug.apk` | APK à installer |
| `claude-android-server/server.py` | Serveur asyncio HTTP :8765 + WebSocket :8766 |
| `claude-android-server/start_server.sh` | Script de démarrage appelé par l'app |
| `claude-android-server/server.log` | Logs du serveur (stdout + stderr) |
| `.sessions.json` | Historique des sessions de chat |

---

## Rebuild de l'APK

Depuis PRoot Ubuntu :
```bash
export ANDROID_SDK_ROOT=/root/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH
export GRADLE_USER_HOME=/root/.gradle

cd /mnt/sdcard/claude_code/claude-android
bash gradlew assembleCoreDebug --no-daemon
```
APK généré dans `app/build/outputs/apk/core/debug/`.

> **Note aapt2** : `aapt2` x86_64 tourne via `qemu-x86_64`. Le wrapper est dans `/usr/local/bin/aapt2` et la propriété `android.aapt2FromMavenOverride` dans `gradle.properties` pointe dessus.

---

## Dépannage

**"Serveur inaccessible après 60s"**  
→ Vérifier `allow-external-apps = true` dans `~/.termux/termux.properties` puis `termux-reload-settings` dans Termux.  
→ Vérifier les logs : `cat /storage/emulated/0/claude_code/claude-android-server/server.log`

**"ModuleNotFoundError: No module named 'websockets'"**  
→ Dans PRoot Ubuntu : `pip install --target=/usr/local/lib/python3/dist-packages websockets`

**"Permission Termux refusée"**  
→ Paramètres Android → Apps → Claude → Permissions → activer Termux.

**Le serveur ne s'arrête pas avec le bouton**  
→ Tuer manuellement dans PRoot Ubuntu : `pkill -f server.py`

**Termux passe brièvement au premier plan au démarrage**  
→ Normal si Termux n'était pas déjà en mémoire. L'app reprend le premier plan automatiquement après ~400ms.

**Aucune réponse de Claude malgré le serveur vert**  
→ Vérifier que Claude CLI est bien connecté : `proot-distro login ubuntu` puis `claude --version`.
