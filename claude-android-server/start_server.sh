#!/data/data/com.termux/files/usr/bin/bash
# Lance le serveur en arrière-plan et sort immédiatement
pgrep -f "server.py" > /dev/null && exit 0   # déjà lancé

nohup proot-distro login ubuntu -- bash -c \
  "source /root/.nvm/nvm.sh && python3 /storage/emulated/0/claude_code/claude-android-server/server.py" \
  > /storage/emulated/0/claude_code/claude-android-server/server.log 2>&1 &

disown
exit 0
