#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_DIR}"
if [[ $# != 2 ]]; then
  echo 'Usage: scripts/dev-check.sh backend <TestClass[,OtherTest]> | frontend <src/path.spec.ts>' >&2
  exit 2
fi
case "$1" in
  backend)
    [[ "$2" =~ ^[a-zA-Z0-9_.$,*#]+$ && "$2" != '*' ]] || { echo 'Provide a focused Java test selector' >&2; exit 2; }
    if [[ "$(uname -s)" == Darwin && -x /usr/libexec/java_home ]]; then
      export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    fi
    ./mvnw -Pbackend-dev "-Dtest=$2" test
    ;;
  frontend)
    [[ "$2" == src/*.spec.ts && "$2" != *..* && -f "frontend/$2" ]] || { echo 'Provide an existing frontend src/*.spec.ts file' >&2; exit 2; }
    cd frontend
    npm run typecheck
    npm run test -- "$2"
    ;;
  *) echo "Unknown check scope: $1" >&2; exit 2 ;;
esac
