#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

if [[ ! -x "${PROJECT_DIR}/mvnw" ]]; then
  echo "Maven Wrapper is missing: ${PROJECT_DIR}/mvnw" >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required for hot development" >&2
  exit 1
fi

cleanup() {
  if [[ -n "${BACKEND_PID:-}" ]]; then
    kill "${BACKEND_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

cd "${PROJECT_DIR}"
./mvnw spring-boot:run &
BACKEND_PID=$!

if [[ -f frontend/package-lock.json ]]; then
  npm --prefix frontend ci
else
  npm --prefix frontend install
fi
npm --prefix frontend run dev
