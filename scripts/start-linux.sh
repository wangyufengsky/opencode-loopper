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
elif [[ -f "${APP_HOME}/target/opencode-loopper-0.1.20.jar" ]]; then
  JAR_PATH="${APP_HOME}/target/opencode-loopper-0.1.20.jar"
elif [[ -f "${APP_HOME}/opencode-loopper-0.1.20.jar" ]]; then
  JAR_PATH="${APP_HOME}/opencode-loopper-0.1.20.jar"
else
  fail "找不到成品 JAR。请把 opencode-loopper-0.1.20.jar 放到 ${APP_HOME}，或设置 LOOPPER_JAR_PATH。"
fi

[[ -f "${JAR_PATH}" ]] || fail "JAR 不存在：${JAR_PATH}"

export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"
export LOOPPER_DATA_DIR="${LOOPPER_DATA_DIR:-${APP_HOME}/data}"
export LOOPPER_DESIGNER_TIMEOUT="${LOOPPER_DESIGNER_TIMEOUT:-30m}"
export SERVER_PORT="${SERVER_PORT:-8080}"

mkdir -p "${LOOPPER_DATA_DIR}"

APP_URL="http://127.0.0.1:${SERVER_PORT}"

# OpenCode itself uses OPENCODE_SERVER_USERNAME/PASSWORD. Reuse those values
# when the operator did not supply Loopper's compatibility aliases explicitly.
if [[ -z "${OPENCODE_PASSWORD:-}" && -n "${OPENCODE_SERVER_PASSWORD:-}" ]]; then
  export OPENCODE_PASSWORD="${OPENCODE_SERVER_PASSWORD}"
fi
if [[ -z "${OPENCODE_USERNAME:-}" && -n "${OPENCODE_PASSWORD:-}" ]]; then
  export OPENCODE_USERNAME="${OPENCODE_SERVER_USERNAME:-opencode}"
fi

opencode_health() {
  local base_url="$1"
  local max_time="${2:-3}"
  local curl_args=(--fail --silent --show-error --max-time "${max_time}")
  if [[ -n "${OPENCODE_USERNAME:-}" ]]; then
    curl_args+=(--user "${OPENCODE_USERNAME}:${OPENCODE_PASSWORD:-}")
  fi
  curl "${curl_args[@]}" "${base_url%/}/global/health" 2>/dev/null \
    | grep -Eq '"healthy"[[:space:]]*:[[:space:]]*true'
}

discover_opencode_base_url() {
  command -v ps >/dev/null 2>&1 || return 1
  command -v curl >/dev/null 2>&1 || return 1

  local process_line pid command_line listener_line listener_address port candidate
  local checked_ports=" "
  local -a opencode_pids=()
  local -a candidate_ports=()
  while IFS= read -r process_line; do
    read -r pid command_line <<< "${process_line}"
    [[ "${pid}" =~ ^[0-9]+$ ]] || continue
    [[ "${command_line}" =~ (^|[[:space:]/])opencode([[:space:]]|$) ]] || continue
    opencode_pids+=("${pid}")
    if [[ "${command_line}" =~ --port=([0-9]{1,5})([[:space:]]|$) ]]; then
      candidate_ports+=("${BASH_REMATCH[1]}")
    elif [[ "${command_line}" =~ --port[[:space:]]+([0-9]{1,5})([[:space:]]|$) ]]; then
      candidate_ports+=("${BASH_REMATCH[1]}")
    fi
  done < <(ps -eo pid=,args= 2>/dev/null | sort -rn)

  # OpenCode TUI and `opencode web` also start an HTTP server, often on a
  # dynamically selected port that is absent from the command line. Resolve
  # listening ports only for already identified OpenCode PIDs, then require the
  # OpenCode health contract before accepting an endpoint.
  for pid in "${opencode_pids[@]:-}"; do
    [[ -n "${pid}" ]] || continue
    if command -v lsof >/dev/null 2>&1; then
      while IFS= read -r listener_line; do
        [[ "${listener_line}" == n* ]] || continue
        listener_address="${listener_line#n}"
        port="${listener_address##*:}"
        [[ "${port}" =~ ^[0-9]{1,5}$ ]] && candidate_ports+=("${port}")
      done < <(lsof -nP -a -p "${pid}" -iTCP -sTCP:LISTEN -Fn 2>/dev/null || true)
    fi
    if command -v ss >/dev/null 2>&1; then
      while IFS= read -r listener_line; do
        [[ "${listener_line}" =~ pid=${pid}, ]] || continue
        read -r _ _ _ listener_address _ <<< "${listener_line}"
        port="${listener_address##*:}"
        [[ "${port}" =~ ^[0-9]{1,5}$ ]] && candidate_ports+=("${port}")
      done < <(ss -H -ltnp 2>/dev/null || true)
    fi
  done

  # Linux may hide socket ownership from an unprivileged process (the same
  # `ss` row is only annotated with users/PIDs under sudo). In that case use
  # the bounded set of local TCP listeners as candidates, but never infer
  # identity from the port: every candidate still has to satisfy OpenCode's
  # exact /global/health JSON contract over loopback.
  if command -v ss >/dev/null 2>&1; then
    while IFS= read -r listener_line; do
      read -r _ _ _ listener_address _ <<< "${listener_line}"
      port="${listener_address##*:}"
      [[ "${port}" =~ ^[0-9]{1,5}$ ]] && candidate_ports+=("${port}")
    done < <(ss -H -ltn 2>/dev/null || true)
  elif command -v lsof >/dev/null 2>&1; then
    while IFS= read -r listener_line; do
      [[ "${listener_line}" == n* ]] || continue
      listener_address="${listener_line#n}"
      port="${listener_address##*:}"
      [[ "${port}" =~ ^[0-9]{1,5}$ ]] && candidate_ports+=("${port}")
    done < <(lsof -nP -iTCP -sTCP:LISTEN -Fn 2>/dev/null || true)
  fi

  for port in "${candidate_ports[@]:-}"; do
    [[ -n "${port}" ]] || continue
    (( port >= 1 && port <= 65535 )) || continue
    [[ "${checked_ports}" == *" ${port} "* ]] && continue
    checked_ports+="${port} "
    candidate="http://127.0.0.1:${port}"
    if opencode_health "${candidate}" 1; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

OPENCODE_BASE_URL_SOURCE="environment"
if [[ "${OPENCODE_BASE_URL:-}" =~ ^http://0\.0\.0\.0:([0-9]{1,5})/?$ ]]; then
  export OPENCODE_BASE_URL="http://127.0.0.1:${BASH_REMATCH[1]}"
  OPENCODE_BASE_URL_SOURCE="environment wildcard normalized to loopback"
elif [[ "${OPENCODE_BASE_URL:-}" =~ ^http://\[::\]:([0-9]{1,5})/?$ ]]; then
  export OPENCODE_BASE_URL="http://[::1]:${BASH_REMATCH[1]}"
  OPENCODE_BASE_URL_SOURCE="environment wildcard normalized to loopback"
elif [[ -z "${OPENCODE_BASE_URL:-}" ]]; then
  OPENCODE_BASE_URL_SOURCE="managed auto startup"
  if DISCOVERED_OPENCODE_BASE_URL="$(discover_opencode_base_url)"; then
    export OPENCODE_BASE_URL="${DISCOVERED_OPENCODE_BASE_URL}"
    OPENCODE_BASE_URL_SOURCE="running opencode process"
  fi
fi

if [[ -z "${LOOPPER_OPENCODE_MODE:-}" ]]; then
  if [[ -n "${OPENCODE_BASE_URL:-}" ]]; then
    export LOOPPER_OPENCODE_MODE="http"
  else
    export LOOPPER_OPENCODE_MODE="auto"
  fi
fi

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
if [[ -n "${OPENCODE_BASE_URL:-}" ]]; then
  echo "[Loopper] OpenCode：${OPENCODE_BASE_URL}（来源：${OPENCODE_BASE_URL_SOURCE}）"
else
  echo "[Loopper] OpenCode：未发现可复用端点，将由 auto 模式在动态 loopback 端口启动"
fi
echo "[Loopper] 项目公约超时：${LOOPPER_DESIGNER_TIMEOUT}"
echo "[Loopper] 页面：${APP_URL}"
echo "[Loopper] Java AWT：${JAVA_AWT_MODE}"

if [[ -n "${OPENCODE_BASE_URL:-}" ]] && command -v curl >/dev/null 2>&1; then
  if ! opencode_health "${OPENCODE_BASE_URL}"; then
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
