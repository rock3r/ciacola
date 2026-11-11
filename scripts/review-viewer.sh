#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

CONV_ID="${CIACOLA_CONVERSATION_ID:-${CONVERSATION_ID:-}}"
if [[ "${1:-}" == "--conversation" && -n "${2:-}" ]]; then
  CONV_ID="$2"
  shift 2
fi

if [[ -n "$CONV_ID" ]]; then
  export CIACOLA_CONVERSATION_ID="$CONV_ID"
fi

if [[ -d "$ROOT_DIR/.gradle-agent-home" ]]; then
  export GRADLE_USER_HOME="$ROOT_DIR/.gradle-agent-home"
  exec ./gradlew --gradle-user-home "$GRADLE_USER_HOME" \
    "-Dkotlin.daemon.options=runFilesPath=$GRADLE_USER_HOME/kotlin-daemon" \
    :deck:hotRunDesktop "$@"
else
  exec ./gradlew :deck:hotRunDesktop "$@"
fi
