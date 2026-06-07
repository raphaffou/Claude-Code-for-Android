#!/data/data/com.termux/files/usr/bin/bash
proot-distro login ubuntu -- bash -c \
  "source /root/.nvm/nvm.sh && claude --help 2>&1" \
  > /storage/emulated/0/claude_code/claude-android-server/claude_help.txt 2>&1
echo "done"
