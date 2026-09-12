#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 4 ]]; then
  echo 'Usage: database-acceptance.sh <loopper.jar> <data-directory> <probe.json> <new-report.json>' >&2
  exit 2
fi
java -Dloader.main=io.opencode.loopper.service.assist.DatabaseOfflineAcceptance -cp "$1" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$2" "$3" "$4"
