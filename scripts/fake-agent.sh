#!/bin/sh
# Fake interactive agent for OpenHerdr dev: reports state to herdr and echoes input.
rep() { herdr pane report-agent "$HERDR_PANE_ID" --source custom:fake --agent fake --state "$1" >/dev/null 2>&1; }
rep idle
while :; do
  printf 'fake> '
  IFS= read -r line || exit 0
  rep working; echo "got: $line"; sleep 1
  case "$line" in
    *approve*) printf 'Allow edit to file.txt? [y/n] '; rep blocked; IFS= read -r ans; echo "answer: $ans"; rep idle ;;
    *) rep idle ;;
  esac
done
