#!/bin/sh
# Runs the integration tests against scripts/it-sshd.sh (state dir $1, default /tmp/oh-it).
set -eu
cd "$(dirname "$0")/.."
export OPENHERDR_IT_DIR="${1:-/tmp/oh-it}"
[ $# -gt 0 ] && shift
export OPENHERDR_IT_PORT="${OPENHERDR_IT_PORT:-22022}"
export OPENHERDR_IT_HERDR_SESSION="${OPENHERDR_IT_HERDR_SESSION:-ohdev}"
exec ./gradlew :app:testDebugUnitTest --tests 'dev.cambraia.openherdr.it.*' --rerun-tasks "$@"
