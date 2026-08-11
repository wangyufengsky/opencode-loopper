#!/usr/bin/env bash

# 有些用户会执行 `sh start-linux.sh`。Ubuntu/Debian 的 sh 通常是 dash，
# 在解析任何 Bash 专用语法前，先显式切换回 Bash。
if [ -z "${BASH_VERSION:-}" ]; then
  if command -v bash >/dev/null 2>&1; then
    exec bash "$0" "$@"
  fi
  echo "[Loopper] 错误：此脚本需要 Bash，但系统中没有找到 bash。" >&2
  exit 1
fi

set -Eeuo pipefail

# 内网 Linux 默认 JDK 目录。也可以在启动时用 LOOPPER_JAVA_HOME 覆盖。
DEFAULT_JAVA_HOME="/opt/jdk-21"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/../pom.xml" ]]; then
  APP_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"
else
  # 允许把本脚本和成品 JAR 一起复制到独立部署目录。
  APP_HOME="${SCRIPT_DIR}"
fi

if [[ -n "${LOOPPER_JAVA_HOME:-}" ]]; then
  JAVA_HOME="${LOOPPER_JAVA_HOME}"
  JAVA_HOME_SOURCE="LOOPPER_JAVA_HOME"
else
  # 不继承系统中可能残留的 JDK 8 JAVA_HOME；脚本内配置必须确定生效。
  JAVA_HOME="${DEFAULT_JAVA_HOME}"
  JAVA_HOME_SOURCE="DEFAULT_JAVA_HOME"
fi
JAVA_BIN="${JAVA_HOME}/bin/java"

fail() {
  echo "[Loopper] 错误：$*" >&2
  exit 1
}

if [[ ! -x "${JAVA_BIN}" ]]; then
  fail "找不到可执行的 Java：${JAVA_BIN}。请修改 DEFAULT_JAVA_HOME，或设置 LOOPPER_JAVA_HOME=/实际/jdk目录。"
fi

JAVA_VERSION_LINE="$("${JAVA_BIN}" -version 2>&1 | head -n 1)"
if [[ "${JAVA_VERSION_LINE}" =~ \"([0-9]+)(\.([0-9]+))? ]]; then
  JAVA_MAJOR="${BASH_REMATCH[1]}"
  if [[ "${JAVA_MAJOR}" == "1" ]]; then
    JAVA_MAJOR="${BASH_REMATCH[3]}"
  fi
else
  fail "无法识别 Java 版本：${JAVA_VERSION_LINE}"
fi
if (( JAVA_MAJOR < 21 )); then
  fail "需要 JDK 21 或更高版本，当前为：${JAVA_VERSION_LINE}"
fi

if [[ -n "${LOOPPER_JAR_PATH:-}" ]]; then
  JAR_PATH="${LOOPPER_JAR_PATH}"
elif [[ -f "${APP_HOME}/target/opencode-loopper-0.1.13.jar" ]]; then
  JAR_PATH="${APP_HOME}/target/opencode-loopper-0.1.13.jar"
elif [[ -f "${APP_HOME}/opencode-loopper-0.1.13.jar" ]]; then
  JAR_PATH="${APP_HOME}/opencode-loopper-0.1.13.jar"
else
  fail "找不到成品 JAR。请把 opencode-loopper-0.1.13.jar 放到 ${APP_HOME}，或设置 LOOPPER_JAR_PATH。"
fi

[[ -f "${JAR_PATH}" ]] || fail "JAR 不存在：${JAR_PATH}"

export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"
export LOOPPER_DATA_DIR="${LOOPPER_DATA_DIR:-${APP_HOME}/data}"
export LOOPPER_OPENCODE_MODE="${LOOPPER_OPENCODE_MODE:-http}"
export OPENCODE_BASE_URL="${OPENCODE_BASE_URL:-http://127.0.0.1:4096}"
export LOOPPER_DESIGNER_TIMEOUT="${LOOPPER_DESIGNER_TIMEOUT:-30m}"
export SERVER_PORT="${SERVER_PORT:-8080}"

mkdir -p "${LOOPPER_DATA_DIR}"

APP_URL="http://127.0.0.1:${SERVER_PORT}"

if [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
  JAVA_AWT_HEADLESS="false"
  JAVA_AWT_MODE="图形模式（允许打开桌面文件夹选择器）"
else
  JAVA_AWT_HEADLESS="true"
  JAVA_AWT_MODE="无图形模式（请直接填写服务器绝对路径）"
fi

echo "[Loopper] JDK：${JAVA_HOME}"
echo "[Loopper] JDK 来源：${JAVA_HOME_SOURCE}"
echo "[Loopper] Java：${JAVA_VERSION_LINE}"
echo "[Loopper] JAR：${JAR_PATH}"
echo "[Loopper] 数据目录：${LOOPPER_DATA_DIR}"
echo "[Loopper] OpenCode：${OPENCODE_BASE_URL}"
echo "[Loopper] 项目公约超时：${LOOPPER_DESIGNER_TIMEOUT}"
echo "[Loopper] 页面：${APP_URL}"
echo "[Loopper] Java AWT：${JAVA_AWT_MODE}"

if [[ -n "${OPENCODE_USERNAME:-}" ]]; then
  echo "[Loopper] OpenCode 已配置认证，跳过匿名健康探测，由应用使用认证信息连接。"
elif command -v curl >/dev/null 2>&1; then
  if ! curl --fail --silent --show-error --max-time 3 "${OPENCODE_BASE_URL%/}/global/health" >/dev/null; then
    echo "[Loopper] 警告：当前无法访问 OpenCode 健康检查；Loopper 仍会启动，但 Runtime 会显示离线。" >&2
  fi
fi

open_browser_when_ready() {
  [[ "${LOOPPER_OPEN_BROWSER:-true}" == "true" ]] || return 0
  command -v curl >/dev/null 2>&1 || return 0
  command -v xdg-open >/dev/null 2>&1 || return 0
  [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] || return 0

  local attempt
  for attempt in {1..60}; do
    if curl --fail --silent --max-time 1 "${APP_URL}/actuator/health" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      xdg-open "${APP_URL}" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  echo "[Loopper] 60 秒内未通过健康检查，请查看当前终端中的启动日志。" >&2
}

open_browser_when_ready &

# 保持 Java 在前台运行，Ctrl+C 会正常停止 Spring Boot。
exec "${JAVA_BIN}" "-Djava.awt.headless=${JAVA_AWT_HEADLESS}" -jar "${JAR_PATH}" "$@"
