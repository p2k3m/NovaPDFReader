#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/firebase_run_screenshot_harness.sh --app <debug.apk> --test <androidTest.apk> [options]

Options:
  --app <path>                 Path to the debug application APK (required).
  --test <path>                Path to the instrumentation APK (required).
  --device <spec>              Device spec passed directly to gcloud (may be repeated).
  --timeout <seconds>          Timeout in seconds for the Firebase Test Lab run (default: 900).
  --results-bucket <gs://...>  Optional Cloud Storage bucket for results.
  --results-dir <name>         Optional directory within the bucket for results.
  --results-history-name <id>  Optional history name used by gcloud.
  --environment <key=value>    Additional instrumentation environment variables (may repeat).
  --dry-run                    Print the gcloud command without executing it.
  -h, --help                   Show this message.
USAGE
}

APP_APK=""
TEST_APK=""
TIMEOUT_SECS=900
DRY_RUN=0
GCLOUD_BIN=${GCLOUD:-gcloud}
PACKAGE_NAME=${PACKAGE_NAME:-com.novapdf.reader}
HARNESS_CLASS=${HARNESS_CLASS_OVERRIDE:-com.novapdf.reader.ScreenshotHarnessTest#openThousandPageDocumentForScreenshots}
RESULTS_BUCKET=""
RESULTS_DIR=""
RESULTS_HISTORY=""
DEVICE_SPECS=()
EXTRA_ENV=()

while [ $# -gt 0 ]; do
  case "$1" in
    --app)
      APP_APK="${2:-}"
      shift 2
      ;;
    --test)
      TEST_APK="${2:-}"
      shift 2
      ;;
    --device)
      DEVICE_SPECS+=("${2:-}")
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECS="${2:-}"
      shift 2
      ;;
    --results-bucket)
      RESULTS_BUCKET="${2:-}"
      shift 2
      ;;
    --results-dir)
      RESULTS_DIR="${2:-}"
      shift 2
      ;;
    --results-history-name)
      RESULTS_HISTORY="${2:-}"
      shift 2
      ;;
    --environment)
      EXTRA_ENV+=("${2:-}")
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ -z "$APP_APK" ] || [ -z "$TEST_APK" ]; then
  echo "Both --app and --test must be specified" >&2
  usage
  exit 1
fi

if [ ! -f "$APP_APK" ]; then
  echo "Application APK not found: $APP_APK" >&2
  exit 1
fi

if [ ! -f "$TEST_APK" ]; then
  echo "Test APK not found: $TEST_APK" >&2
  exit 1
fi

if ! command -v "$GCLOUD_BIN" >/dev/null 2>&1; then
  echo "gcloud CLI not found; please install the Google Cloud SDK or set GCLOUD to an alternate path" >&2
  exit 1
fi

ensure_gcloud_authentication() {
  local active_account
  local temp_key_file=""
  local cleanup_temp_key=0

  active_account="$($GCLOUD_BIN auth list --format='value(account)' --filter='status:ACTIVE' 2>/dev/null || true)"
  if [ -n "$active_account" ]; then
    return
  fi

  if [ -n "${NOVAPDF_FTL_SERVICE_ACCOUNT_KEY:-}" ]; then
    temp_key_file="$(mktemp)"
    printf '%s' "${NOVAPDF_FTL_SERVICE_ACCOUNT_KEY}" >"$temp_key_file"
    cleanup_temp_key=1
  elif [ -n "${NOVAPDF_FTL_SERVICE_ACCOUNT_KEY_B64:-}" ]; then
    temp_key_file="$(mktemp)"
    if ! python3 - "$temp_key_file" <<'PY'
import base64
import os
import sys

destination = sys.argv[1]
payload = os.environ.get("NOVAPDF_FTL_SERVICE_ACCOUNT_KEY_B64", "")

try:
    decoded = base64.b64decode(payload)
except Exception as exc:  # pragma: no cover - defensive guard
    raise SystemExit(f"Failed to decode NOVAPDF_FTL_SERVICE_ACCOUNT_KEY_B64: {exc}")

with open(destination, "wb") as fh:
    fh.write(decoded)
PY
    then
      echo "Failed to decode NOVAPDF_FTL_SERVICE_ACCOUNT_KEY_B64" >&2
      rm -f "$temp_key_file"
      exit 1
    fi
    cleanup_temp_key=1
  elif [ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ] && [ -f "${GOOGLE_APPLICATION_CREDENTIALS}" ]; then
    temp_key_file="${GOOGLE_APPLICATION_CREDENTIALS}"
  fi

  if [ -n "$temp_key_file" ]; then
    if ! "$GCLOUD_BIN" auth activate-service-account --key-file "$temp_key_file" >/dev/null 2>&1; then
      if [ "$cleanup_temp_key" -eq 1 ]; then
        rm -f "$temp_key_file"
      fi
      echo "Failed to activate gcloud service account from provided credentials" >&2
      exit 1
    fi
    if [ "$cleanup_temp_key" -eq 1 ]; then
      rm -f "$temp_key_file"
    fi
  fi

  active_account="$($GCLOUD_BIN auth list --format='value(account)' --filter='status:ACTIVE' 2>/dev/null || true)"
  if [ -n "$active_account" ]; then
    return
  fi

  cat >&2 <<'MSG'
No active gcloud account is configured for Firebase Test Lab.

Please authenticate with one of the following commands before re-running this script:
  gcloud auth login
  gcloud auth activate-service-account --key-file <path-to-service-account-key>
If you have already authenticated, select the correct account:
  gcloud config set account <ACCOUNT>

Alternatively, set one of the following environment variables so the script can
authenticate automatically:
  NOVAPDF_FTL_SERVICE_ACCOUNT_KEY        Raw JSON credentials contents
  NOVAPDF_FTL_SERVICE_ACCOUNT_KEY_B64    Base64-encoded JSON credentials
  GOOGLE_APPLICATION_CREDENTIALS         Path to a JSON credentials file
MSG
  exit 1
}

ensure_gcloud_authentication

if ! python3 - "$TIMEOUT_SECS" <<'PY' >/dev/null 2>&1; then
import sys
try:
    value = float(sys.argv[1])
except Exception:
    raise SystemExit(1)
if value <= 0:
    raise SystemExit(1)
raise SystemExit(0)
PY
  echo "Invalid --timeout value: $TIMEOUT_SECS" >&2
  exit 1
fi

TIMEOUT_SECS=$(python3 - "$TIMEOUT_SECS" <<'PY'
import sys
value = float(sys.argv[1])
print(int(round(value)))
PY
)

base_env=(
  "class=${HARNESS_CLASS}"
  "runScreenshotHarness=true"
)

default_metrics_flag="captureMetrics=true"
if [ -z "${NOVAPDF_FTL_CAPTURE_METRICS:-}" ] || [ "${NOVAPDF_FTL_CAPTURE_METRICS}" != "false" ]; then
  base_env+=("${default_metrics_flag}")
fi

all_env=("${base_env[@]}" "${EXTRA_ENV[@]}")

device_args=()
if [ ${#DEVICE_SPECS[@]} -eq 0 ]; then
  device_args+=("--device" "model=panther,version=34,locale=en,orientation=portrait")
else
  for spec in "${DEVICE_SPECS[@]}"; do
    device_args+=("--device" "$spec")
  done
fi

cmd=(
  "$GCLOUD_BIN" firebase test android run
  --type instrumentation
  --app "$APP_APK"
  --test "$TEST_APK"
  --timeout "${TIMEOUT_SECS}s"
  --environment-variables "$(IFS=,; printf '%s' "${all_env[*]}")"
  --directories-to-pull "/sdcard/Android/data/${PACKAGE_NAME}/files"
)

cmd+=("${device_args[@]}")

if [ -n "$RESULTS_BUCKET" ]; then
  cmd+=("--results-bucket" "$RESULTS_BUCKET")
fi

if [ -n "$RESULTS_DIR" ]; then
  cmd+=("--results-dir" "$RESULTS_DIR")
fi

if [ -n "$RESULTS_HISTORY" ]; then
  cmd+=("--results-history-name" "$RESULTS_HISTORY")
fi

if [ ${#EXTRA_ENV[@]} -gt 0 ]; then
  printf 'Additional instrumentation environment variables:\n' >&2
  printf '  %s\n' "${EXTRA_ENV[@]}" >&2
fi

printf 'Running screenshot harness on Firebase Test Lab with command:\n' >&2
printf '  %q' "${cmd[@]}" >&2
printf '\n' >&2

if [ "$DRY_RUN" -eq 1 ]; then
  exit 0
fi

exec "${cmd[@]}"
