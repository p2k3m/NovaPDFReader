#!/usr/bin/env bash
set -euo pipefail

serial="emulator-5554"
timeout_seconds=900
poll_interval=5
emulator_config_validated=0

print_usage() {
  cat <<'USAGE'
Usage: wait_for_emulator_readiness.sh [--serial SERIAL] [--timeout SECONDS]

Waits until the specified emulator instance reports device readiness signals,
including boot completion, package manager availability, and storage mounts.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for $1" >&2
        exit 1
      fi
      serial="$2"
      shift 2
      ;;
    -t|--timeout)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for $1" >&2
        exit 1
      fi
      if ! [[ "$2" =~ ^[0-9]+$ ]]; then
        echo "Timeout must be numeric seconds" >&2
        exit 1
      fi
      timeout_seconds="$2"
      shift 2
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      print_usage
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb command not available on PATH" >&2
  exit 1
fi

if [[ -n "${ADB_SERIAL:-}" ]]; then
  serial="$ADB_SERIAL"
fi

if [[ -z "$serial" ]]; then
  echo "Emulator serial must not be empty" >&2
  exit 1
fi

declare -r serial

declare -r timeout_seconds

declare -r poll_interval

start_time=$(date +%s)
end_time=$((start_time + timeout_seconds))

check_emulator_pid() {
  local pid="${EMULATOR_PID:-}"
  if [[ -n "$pid" ]]; then
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      echo "Emulator process $pid exited while waiting for readiness" >&2
      exit 1
    fi

    if (( emulator_config_validated == 0 )); then
      validate_emulator_configuration "$pid"
      emulator_config_validated=1
    fi
  fi
}

validate_emulator_configuration() {
  local pid="$1"
  local -a args=()

  if [[ -r "/proc/${pid}/cmdline" ]]; then
    while IFS= read -r -d '' entry; do
      if [[ -n "$entry" ]]; then
        args+=("$entry")
      fi
    done < "/proc/${pid}/cmdline"
  else
    local ps_output
    if ! ps_output=$(ps -ww -p "$pid" -o command= 2>/dev/null); then
      echo "Unable to inspect emulator command line for PID ${pid}" >&2
      exit 1
    fi
    read -r -a args <<< "$ps_output"
  fi

  if (( ${#args[@]} == 0 )); then
    echo "Unable to parse emulator command line for PID ${pid}" >&2
    exit 1
  fi

  local memory_value=""
  local partition_value=""
  local accel_value=""
  local has_no_snapshot_save=0

  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
      -memory)
        if (( i + 1 >= ${#args[@]} )); then
          echo "Emulator launched without a value for -memory; expected at least 4096" >&2
          exit 1
        fi
        memory_value="${args[$((i + 1))]}"
        ;;
      -partition-size)
        if (( i + 1 >= ${#args[@]} )); then
          echo "Emulator launched without a value for -partition-size; expected at least 4096" >&2
          exit 1
        fi
        partition_value="${args[$((i + 1))]}"
        ;;
      -accel)
        if (( i + 1 >= ${#args[@]} )); then
          echo "Emulator launched without a value for -accel; expected 'on'" >&2
          exit 1
        fi
        accel_value="${args[$((i + 1))]}"
        ;;
      -no-snapshot-save)
        has_no_snapshot_save=1
        ;;
    esac
  done

  if [[ -z "$memory_value" ]]; then
    echo "Emulator PID ${pid} missing -memory flag; launch with at least -memory 4096" >&2
    exit 1
  fi
  if ! [[ "$memory_value" =~ ^[0-9]+$ ]]; then
    echo "Emulator PID ${pid} has non-numeric -memory value '${memory_value}'" >&2
    exit 1
  fi
  if (( memory_value < 4096 )); then
    echo "Emulator PID ${pid} configured with ${memory_value} MiB RAM; expected at least 4096" >&2
    exit 1
  fi

  if [[ -z "$partition_value" ]]; then
    echo "Emulator PID ${pid} missing -partition-size flag; launch with at least -partition-size 4096" >&2
    exit 1
  fi
  if ! [[ "$partition_value" =~ ^[0-9]+$ ]]; then
    echo "Emulator PID ${pid} has non-numeric -partition-size value '${partition_value}'" >&2
    exit 1
  fi
  if (( partition_value < 4096 )); then
    echo "Emulator PID ${pid} configured with ${partition_value} MiB data partition; expected at least 4096" >&2
    exit 1
  fi

  if [[ -z "$accel_value" ]]; then
    echo "Emulator PID ${pid} missing -accel flag; launch with hardware acceleration enabled (-accel on)" >&2
    exit 1
  fi
  local accel_normalized=${accel_value,,}
  if [[ "$accel_normalized" != "on" && "$accel_normalized" != "auto" ]]; then
    echo "Emulator PID ${pid} launched with -accel ${accel_value}; expected hardware acceleration (on/auto)" >&2
    exit 1
  fi

  if (( has_no_snapshot_save == 0 )); then
    echo "Emulator PID ${pid} missing -no-snapshot-save; launch with snapshots disabled" >&2
    exit 1
  fi

  echo "Verified emulator PID ${pid} meets memory, storage, and acceleration requirements." >&2
}

remaining_time() {
  local now
  now=$(date +%s)
  echo $((end_time - now))
}

ensure_within_deadline() {
  local remaining
  remaining=$(remaining_time)
  if (( remaining <= 0 )); then
    echo "$1" >&2
    exit 1
  fi
}

wait_until() {
  local description="$1"
  shift
  while true; do
    if "$@"; then
      return 0
    fi
    check_emulator_pid
    ensure_within_deadline "Timed out waiting for ${description}"
    sleep "$poll_interval"
  done
}

adb_shell() {
  adb -s "$serial" shell "$@"
}

check_property_equals() {
  local property="$1"
  local expected_csv="$2"
  local value
  value=$(adb_shell getprop "$property" 2>/dev/null | tr -d '\r' | tr -d '\n') || value=""
  IFS='|' read -r -a expected_values <<< "$expected_csv"
  for candidate in "${expected_values[@]}"; do
    if [[ "$value" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

check_settings_value() {
  local namespace="$1"
  local key="$2"
  local expected_csv="$3"
  local value
  value=$(adb_shell settings get "$namespace" "$key" 2>/dev/null | tr -d '\r' | tr -d '\n') || value=""
  IFS='|' read -r -a expected_values <<< "$expected_csv"
  for candidate in "${expected_values[@]}"; do
    if [[ "$value" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

check_service_registered() {
  local service="$1"
  adb_shell service check "$service" 2>/dev/null | tr -d '\r' | grep -qE ': found$'
}

check_pm_command() {
  adb_shell cmd package list packages >/dev/null 2>&1
}

check_content_provider() {
  local uri="$1"
  adb_shell content query --uri "$uri" --user 0 --limit 1 >/dev/null 2>&1
}

check_storage_available() {
  adb_shell ls /sdcard >/dev/null 2>&1
}

wait_for_device_online() {
  adb -s "$serial" wait-for-device
}

check_sys_boot_completed() {
  check_property_equals "sys.boot_completed" "1"
}

check_dev_bootcomplete() {
  check_property_equals "dev.bootcomplete" "1"
}

check_bootanim_stopped() {
  check_property_equals "init.svc.bootanim" "stopped|"
}

check_ce_available() {
  check_property_equals "sys.user.0.ce_available" "1|true"
}

check_device_provisioned() {
  check_settings_value global device_provisioned "1|true"
}

check_user_setup_complete() {
  check_settings_value secure user_setup_complete "1|true"
}

check_settings_provider_ready() {
  check_content_provider "content://settings/global"
}

check_storage_manager() {
  check_service_registered mount
}

check_ims_ready() {
  adb_shell getprop vendor.ims.ENABLED >/dev/null 2>&1
}

wait_until "ADB connection to $serial" wait_for_device_online
wait_until "system boot completion" check_sys_boot_completed
wait_until "device boot completion" check_dev_bootcomplete
wait_until "boot animation to stop" check_bootanim_stopped
wait_until "credential encrypted storage" check_ce_available
wait_until "device provisioning" check_device_provisioned
wait_until "user setup" check_user_setup_complete
wait_until "settings provider availability" check_settings_provider_ready
wait_until "package manager binder" check_service_registered package
wait_until "package manager command interface" check_pm_command
wait_until "storage manager service" check_storage_manager
wait_until "external storage mount" check_storage_available
wait_until "IMS ready property" check_ims_ready

# Final sanity ping to ensure shell responsiveness
if ! adb_shell uptime >/dev/null 2>&1; then
  echo "adb shell responsiveness check failed" >&2
  exit 1
fi

exit 0
