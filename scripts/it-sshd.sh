#!/bin/sh
# Runs a throwaway, unprivileged sshd on 127.0.0.1 for OpenHerdr integration tests.
# Usage: scripts/it-sshd.sh <state-dir> [port]   (foreground; Ctrl+C to stop)
set -eu
DIR="$1"; PORT="${2:-22022}"
mkdir -p "$DIR"
[ -f "$DIR/host_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -f "$DIR/host_ed25519"
touch "$DIR/authorized_keys"; chmod 600 "$DIR/authorized_keys"
cat > "$DIR/sshd_config" <<CONF
Port $PORT
ListenAddress 127.0.0.1
HostKey $DIR/host_ed25519
PidFile $DIR/sshd.pid
AuthorizedKeysFile $DIR/authorized_keys
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
AllowStreamLocalForwarding yes
Subsystem sftp internal-sftp
CONF
exec /usr/sbin/sshd -D -e -f "$DIR/sshd_config"
