#!/bin/bash
# Finder 双击或从任意工作目录启动；共用 Unix 启动逻辑。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec /bin/bash "${SCRIPT_DIR}/start-linux.sh" "$@"
