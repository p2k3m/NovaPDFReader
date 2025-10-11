#!/usr/bin/env bash

# Copyright (c) 2024 NovaPDFReader
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <emulator-pid> <adb-serial> [grace-seconds] [unhealthy-seconds] [check-interval]" >&2
  exit 64
fi

emulator_pid="$1"
adb_serial="$2"
grace_seconds="${3:-900}"
unhealthy_seconds="${4:-600}"
check_interval="${5:-60}"

log() {
  local message="$1"
  printf '[watchdog] %s\n' "$message" >&2
}

collect_diagnostics() {
  local tombstones traces

  log "Attempting to collect emulator diagnostics from serial ${adb_serial}"

  if timeout 30s adb -s "$adb_serial" root >/dev/null 2>&1; then
    log "ADB root enabled for diagnostics"
  fi

  if tombstones=$(timeout 45s adb -s "$adb_serial" shell ls /data/tombstones 2>/dev/null | tr -d '\r' | tr ' ' '\n'); then
    tombstones=$(printf '%s\n' "$tombstones" | sed '/^$/d')
  else
    tombstones=""
  fi

  if [ -n "$tombstones" ]; then
    log "Found tombstones:"
    printf '%s\n' "$tombstones" >&2
    while IFS= read -r tombstone; do
      log "===== /data/tombstones/${tombstone} ====="
      timeout 60s adb -s "$adb_serial" shell cat "/data/tombstones/${tombstone}" >&2 || log "Failed to read ${tombstone}"
    done <<< "$tombstones"
  else
    log "No tombstones detected under /data/tombstones"
  fi

  if traces=$(timeout 45s adb -s "$adb_serial" shell ls /data/anr 2>/dev/null | tr -d '\r' | tr ' ' '\n'); then
    traces=$(printf '%s\n' "$traces" | sed '/^$/d')
  else
    traces=""
  fi

  if printf '%s\n' "$traces" | grep -q '^traces.txt$'; then
    log "===== /data/anr/traces.txt ====="
    timeout 60s adb -s "$adb_serial" shell cat /data/anr/traces.txt >&2 || log "Failed to read traces.txt"
  else
    log "No traces.txt present under /data/anr"
  fi
}

kill_emulator() {
  local attempts=("adb" "signal")

  for attempt in "${attempts[@]}"; do
    case "$attempt" in
      adb)
        if timeout 30s adb -s "$adb_serial" emu kill >/dev/null 2>&1; then
          log "Issued adb emu kill to serial ${adb_serial}"
          sleep 5
        fi
        ;;
      signal)
        if kill -0 "$emulator_pid" >/dev/null 2>&1; then
          log "Sending SIGKILL to emulator PID ${emulator_pid}"
          kill -9 "$emulator_pid" >/dev/null 2>&1 || true
        fi
        ;;
    esac
  done
}

start_time=$(date +%s)
grace_deadline=$((start_time + grace_seconds))
consecutive_unhealthy=0

log "Started watchdog for emulator PID ${emulator_pid} (serial ${adb_serial})"

while kill -0 "$emulator_pid" >/dev/null 2>&1; do
  if timeout 30s adb -s "$adb_serial" get-state >/dev/null 2>&1; then
    consecutive_unhealthy=0
  else
    now=$(date +%s)
    if [ "$now" -lt "$grace_deadline" ]; then
      log "Health probe failed during grace period (${grace_seconds}s); continuing to monitor"
    else
      consecutive_unhealthy=$((consecutive_unhealthy + check_interval))
      log "Health probe failed for ${consecutive_unhealthy} seconds (threshold ${unhealthy_seconds}s)"
      if [ "$consecutive_unhealthy" -ge "$unhealthy_seconds" ]; then
        log "::error::Emulator watchdog detected serial ${adb_serial} unresponsive for ${consecutive_unhealthy} seconds"
        collect_diagnostics
        kill_emulator
        exit 1
      fi
    fi
  fi

  sleep "$check_interval"
done

log "Emulator PID ${emulator_pid} exited; stopping watchdog"
exit 0
