#!/usr/bin/env bash
set -euo pipefail

log() {
  local timestamp
  timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  printf '[%s] %s\n' "$timestamp" "$*" >&2
}

fatal() {
  log "ERROR: $*"
  exit 1
}

trim_whitespace() {
  local value="$1"
  # Trim leading whitespace
  value="${value#${value%%[!$' \t\r\n']*}}"
  # Trim trailing whitespace
  value="${value%${value##*[!$' \t\r\n']}}"
  printf '%s' "$value"
}

usage() {
  cat <<'USAGE' >&2
Usage: automated_pdf_render.sh --apk <path> --package <name> [options]

Options:
  --apk <path>              Path to the application APK to install (required).
  --package <name>          Application package name (required).
  --serial <serial>         adb serial to target when using the emulator backend.
  --test-apk <path>         Instrumentation APK required for the Firebase backend.
  --backend <name>          Automation backend: "firebase" (default) or "emulator".

Environment variables:
  NOVAPDF_AUTOMATION_SOURCE_BUCKET   Source S3 bucket (default: pics-1234)
  NOVAPDF_AUTOMATION_SOURCE_KEY      Source S3 key (default: AI.pdf)
  NOVAPDF_AUTOMATION_TARGET_BUCKET   Destination S3 bucket (default: novapdfreader)
  NOVAPDF_AUTOMATION_AVD_NAME        Name for the temporary AVD (default: novapdf-automation-api32)
  NOVAPDF_AUTOMATION_SYSTEM_IMAGE    SDK system image (default: system-images;android-32;google_apis;x86_64)
  NOVAPDF_AUTOMATION_DEVICE_PROFILE  AVD device profile (default: pixel_5)
  NOVAPDF_AUTOMATION_PLATFORM_API    Android platform API level (default: 32)
  NOVAPDF_AUTOMATION_RENDER_WAIT     Seconds to wait after launch before screenshot (default: 10)
  NOVAPDF_AUTOMATION_INITIAL_DELAY   Initial delay before monitoring render logs (default: 5)
  NOVAPDF_AUTOMATION_RENDER_TIMEOUT  Maximum seconds to wait for render confirmation (default: 120)
  NOVAPDF_AUTOMATION_EMULATOR_START_TIMEOUT  Seconds to wait for the emulator to register with adb (default: 180)
  NOVAPDF_AUTOMATION_BACKEND         Default backend selection (firebase or emulator)
  NOVAPDF_AUTOMATION_FIREBASE_DEVICE_SPECS  Comma-separated Firebase device specs for the firebase backend
  NOVAPDF_AUTOMATION_FIREBASE_TIMEOUT       Timeout passed to Firebase harness (default: 900)
  NOVAPDF_AUTOMATION_FIREBASE_RESULTS_BUCKET  Optional Firebase results bucket
  NOVAPDF_AUTOMATION_FIREBASE_RESULTS_DIR     Optional Firebase results directory
  NOVAPDF_AUTOMATION_FIREBASE_RESULTS_HISTORY Optional Firebase results history identifier
  NOVAPDF_AUTOMATION_FIREBASE_ENV             Comma-separated instrumentation environment overrides
  AWS_REGION / AWS_DEFAULT_REGION    AWS region (default: us-east-1)
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APK_PATH=""
PACKAGE_NAME=""
ADB_SERIAL=""
TEST_APK=""
AUTOMATION_BACKEND="${NOVAPDF_AUTOMATION_BACKEND:-firebase}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      [[ $# -ge 2 ]] || fatal "Missing value for --apk"
      APK_PATH="$2"
      shift 2
      ;;
    --package)
      [[ $# -ge 2 ]] || fatal "Missing value for --package"
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --serial)
      [[ $# -ge 2 ]] || fatal "Missing value for --serial"
      ADB_SERIAL="$2"
      shift 2
      ;;
    --test-apk)
      [[ $# -ge 2 ]] || fatal "Missing value for --test-apk"
      TEST_APK="$2"
      shift 2
      ;;
    --backend)
      [[ $# -ge 2 ]] || fatal "Missing value for --backend"
      AUTOMATION_BACKEND="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fatal "Unknown argument: $1"
      ;;
  esac
done

AUTOMATION_BACKEND="${AUTOMATION_BACKEND,,}"
case "$AUTOMATION_BACKEND" in
  emulator|firebase)
    ;;
  *)
    fatal "Unsupported backend '$AUTOMATION_BACKEND'. Expected 'emulator' or 'firebase'"
    ;;
esac

[[ -n "$APK_PATH" ]] || fatal "--apk path is required"
[[ -n "$PACKAGE_NAME" ]] || fatal "--package is required"

if [[ ! -f "$APK_PATH" ]]; then
  fatal "APK path $APK_PATH does not exist"
fi

if [[ "$AUTOMATION_BACKEND" == "firebase" ]]; then
  [[ -n "$TEST_APK" ]] || fatal "--test-apk is required when using the Firebase backend"
  if [[ ! -f "$TEST_APK" ]]; then
    fatal "Test APK path $TEST_APK does not exist"
  fi
fi

if [[ "$AUTOMATION_BACKEND" == "emulator" ]] && ! command -v aws >/dev/null 2>&1; then
  fatal "aws CLI is not installed"
fi

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

run_firebase_backend() {
  local firebase_script="$SCRIPT_DIR/firebase_run_screenshot_harness.sh"

  if [[ ! -x "$firebase_script" ]]; then
    fatal "Firebase harness driver not found at $firebase_script"
  fi

  ensure_firebase_auth() {
    if [[ -n "${NOVAPDF_FTL_SERVICE_ACCOUNT_KEY:-}" || -n "${NOVAPDF_FTL_SERVICE_ACCOUNT_KEY_B64:-}" ]]; then
      return 0
    fi

    if [[ -n "${FIREBASE_SERVICE_ACCOUNT_JSON:-}" ]]; then
      export NOVAPDF_FTL_SERVICE_ACCOUNT_KEY="${NOVAPDF_FTL_SERVICE_ACCOUNT_KEY:-${FIREBASE_SERVICE_ACCOUNT_JSON}}"
      return 0
    fi

    if [[ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
      if [[ -f "$GOOGLE_APPLICATION_CREDENTIALS" ]]; then
        return 0
      fi

      if python3 - <<'PY' >/dev/null 2>&1
import base64
import json
import os
import sys

raw_value = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")

if not raw_value.strip():
    raise SystemExit(1)

stripped = raw_value.lstrip()
if stripped.startswith("{"):
    try:
        json.loads(raw_value)
    except Exception:  # pragma: no cover - defensive guard
        raise SystemExit(1)
    raise SystemExit(0)

try:
    decoded = base64.b64decode(raw_value, validate=True)
except Exception:  # pragma: no cover - defensive guard
    raise SystemExit(1)

try:
    decoded_text = decoded.decode("utf-8")
except Exception:  # pragma: no cover - defensive guard
    raise SystemExit(1)

if not decoded_text.lstrip().startswith("{"):
    raise SystemExit(1)

try:
    json.loads(decoded_text)
except Exception:  # pragma: no cover - defensive guard
    raise SystemExit(1)

raise SystemExit(0)
PY
      then
        return 0
      fi

      fatal "GOOGLE_APPLICATION_CREDENTIALS is set but is neither a readable file nor valid JSON/base64 credentials"
    fi

    if command -v gcloud >/dev/null 2>&1; then
      local active_accounts
      if active_accounts=$(gcloud auth list --filter="status:ACTIVE" --format="value(account)" 2>/dev/null); then
        if [[ -n "${active_accounts//[$'\n\r\t ']}" ]]; then
          return 0
        fi
      fi
      return 1
    fi

    return 1
  }

  if ! ensure_firebase_auth; then
    fatal "Firebase credentials were not detected. Set FIREBASE_SERVICE_ACCOUNT_JSON or configure GOOGLE_APPLICATION_CREDENTIALS for NOVAPDF_AUTOMATION_BACKEND=firebase"
  fi

  local firebase_timeout="${NOVAPDF_AUTOMATION_FIREBASE_TIMEOUT:-}"
  local firebase_bucket="${NOVAPDF_AUTOMATION_FIREBASE_RESULTS_BUCKET:-}"
  local firebase_dir="${NOVAPDF_AUTOMATION_FIREBASE_RESULTS_DIR:-}"
  local firebase_history="${NOVAPDF_AUTOMATION_FIREBASE_RESULTS_HISTORY:-}"
  local firebase_devices="${NOVAPDF_AUTOMATION_FIREBASE_DEVICE_SPECS:-}"
  local firebase_env="${NOVAPDF_AUTOMATION_FIREBASE_ENV:-}"

  local -a cmd=("$firebase_script" --app "$APK_PATH" --test "$TEST_APK")

  if [[ -n "$firebase_timeout" ]]; then
    cmd+=(--timeout "$firebase_timeout")
  fi

  if [[ -n "$firebase_bucket" ]]; then
    cmd+=(--results-bucket "$firebase_bucket")
  fi

  if [[ -n "$firebase_dir" ]]; then
    cmd+=(--results-dir "$firebase_dir")
  fi

  if [[ -n "$firebase_history" ]]; then
    cmd+=(--results-history-name "$firebase_history")
  fi

  if [[ -n "$firebase_devices" ]]; then
    IFS=',' read -r -a device_specs <<<"$firebase_devices"
    for spec in "${device_specs[@]}"; do
      spec="$(trim_whitespace "$spec")"
      if [[ -n "$spec" ]]; then
        cmd+=(--device "$spec")
      fi
    done
  fi

  if [[ -n "$firebase_env" ]]; then
    IFS=',' read -r -a extra_env <<<"$firebase_env"
    for entry in "${extra_env[@]}"; do
      entry="$(trim_whitespace "$entry")"
      if [[ -n "$entry" ]]; then
        cmd+=(--environment "$entry")
      fi
    done
  fi

  if [[ "${NOVAPDF_AUTOMATION_FIREBASE_DRY_RUN:-}" == "true" ]]; then
    cmd+=(--dry-run)
  fi

  export PACKAGE_NAME

  log "Running NovaPDF automation via Firebase Test Lab backend"
  "${cmd[@]}"
}

if [[ "$AUTOMATION_BACKEND" == "firebase" ]]; then
  run_firebase_backend
  exit "$?"
fi

if [[ -z "$ANDROID_HOME" ]]; then
  ANDROID_HOME="$HOME/.novapdf/android-sdk"
  log "ANDROID_HOME not provided; defaulting to $ANDROID_HOME"
else
  if [[ -d "$ANDROID_HOME" ]]; then
    if [[ ! -w "$ANDROID_HOME" ]]; then
      log "ANDROID_HOME=$ANDROID_HOME is not writable; falling back to $HOME/.novapdf/android-sdk"
      ANDROID_HOME="$HOME/.novapdf/android-sdk"
    fi
  else
    if ! mkdir -p "$ANDROID_HOME" >/dev/null 2>&1; then
      log "Unable to create ANDROID_HOME at $ANDROID_HOME; falling back to $HOME/.novapdf/android-sdk"
      ANDROID_HOME="$HOME/.novapdf/android-sdk"
    fi
  fi
fi

mkdir -p "$ANDROID_HOME"

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_HOME/avd}"
mkdir -p "$AVD_HOME"
export ANDROID_AVD_HOME="$AVD_HOME"
log "Using ANDROID_AVD_HOME=$ANDROID_AVD_HOME"

resolve_cmdline_tool() {
  local tool="$1"
  local -a search_dirs=()

  search_dirs+=("$ANDROID_HOME/cmdline-tools/latest/bin")
  search_dirs+=("$ANDROID_HOME/cmdline-tools/bin")

  if [[ -d "$ANDROID_HOME/cmdline-tools" ]]; then
    while IFS= read -r dir; do
      search_dirs+=("$dir")
    done < <(find "$ANDROID_HOME/cmdline-tools" -mindepth 2 -maxdepth 3 -type d -name bin -print 2>/dev/null | sort)
  fi

  for dir in "${search_dirs[@]}"; do
    local candidate="$dir/$tool"
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done

  if command -v "$tool" >/dev/null 2>&1; then
    command -v "$tool"
    return 0
  fi

  return 1
}

resolve_latest_cmdline_tools_url() {
  if ! command -v python3 >/dev/null 2>&1; then
    return 2
  fi

  python3 - <<'PY'
import re
import sys
import urllib.request

REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-1.xml"


def main() -> int:
    try:
        with urllib.request.urlopen(REPOSITORY_URL) as response:
            content = response.read().decode("utf-8", "ignore")
    except Exception as exc:  # noqa: BLE001 - propagate failure details
        print(f"Failed to download repository metadata: {exc}", file=sys.stderr)
        return 1

    versions = {
        int(match)
        for match in re.findall(
            r"commandlinetools-linux-(\d+)_latest\.zip",
            content,
        )
    }

    if not versions:
        print("Unable to locate any commandlinetools-linux archives in metadata", file=sys.stderr)
        return 1

    latest = max(versions)
    print(f"https://dl.google.com/android/repository/commandlinetools-linux-{latest}_latest.zip")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
PY
}

download_archive() {
  local url="$1"
  local dest="$2"

  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$dest"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$url" -O "$dest"
  else
    return 2
  fi
}

check_kvm_support() {
  if [[ "${NOVAPDF_AUTOMATION_SKIP_KVM_CHECK:-}" == "true" ]]; then
    return 0
  fi

  if [[ ! -e /dev/kvm ]]; then
    fatal "KVM acceleration is unavailable (/dev/kvm missing). Set NOVAPDF_AUTOMATION_BACKEND=firebase or enable virtualization."
  fi

  if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    fatal "KVM device /dev/kvm is not accessible. Add the current user to the kvm group or adjust permissions, or use the Firebase backend."
  fi
}

check_emulator_health() {
  local emulator_pid="$1"
  local log_path="$2"

  if [[ -n "$emulator_pid" ]] && ! kill -0 "$emulator_pid" >/dev/null 2>&1; then
    if [[ -f "$log_path" ]]; then
      tail -n 200 "$log_path" >&2 || true
    fi
    fatal "Android emulator process exited unexpectedly"
  fi

  if [[ -f "$log_path" ]] && grep -qi 'x86_64 emulation currently requires hardware acceleration' "$log_path"; then
    tail -n 200 "$log_path" >&2 || true
    fatal "Android emulator reported missing hardware acceleration (KVM permissions required)"
  fi
}

ensure_cmdline_tools() {
  if resolve_cmdline_tool sdkmanager >/dev/null 2>&1 && \
     resolve_cmdline_tool avdmanager >/dev/null 2>&1; then
    return 0
  fi

  local tools_url="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
  local tmp_dir
  tmp_dir="$(mktemp -d)"

  log "Downloading Android command-line tools from $tools_url"

  local archive="$tmp_dir/cmdline-tools.zip"
  if ! download_archive "$tools_url" "$archive"; then
    local status=$?
    if (( status == 2 )); then
      fatal "Neither curl nor wget is available to download Android command-line tools"
    fi

    log "Default command-line tools URL failed, attempting to resolve latest dynamically"

    if ! tools_url="$(resolve_latest_cmdline_tools_url)"; then
      local resolve_status=$?
      if (( resolve_status == 2 )); then
        fatal "python3 is required to resolve the latest Android command-line tools URL"
      fi
      fatal "Failed to resolve latest Android command-line tools URL"
    fi
    log "Retrying download from $tools_url"
    if ! download_archive "$tools_url" "$archive"; then
      (( $? == 2 )) && fatal "Neither curl nor wget is available to download Android command-line tools"
      fatal "Failed to download Android command-line tools"
    fi
  fi

  if ! command -v unzip >/dev/null 2>&1; then
    fatal "unzip is required to install Android command-line tools"
  fi

  unzip -q "$archive" -d "$tmp_dir" || fatal "Failed to extract Android command-line tools"

  local extracted_dir="$tmp_dir/cmdline-tools"
  [[ -d "$extracted_dir" ]] || fatal "Downloaded archive does not contain cmdline-tools directory"

  local install_root="$ANDROID_HOME/cmdline-tools"
  local install_dir="$install_root/latest"
  mkdir -p "$install_root"
  rm -rf "$install_dir"
  mkdir -p "$install_dir"
  cp -a "$extracted_dir"/. "$install_dir"/ || fatal "Failed to install Android command-line tools"

  rm -rf "$tmp_dir"

  log "Android command-line tools installed under $install_dir"
}

ensure_cmdline_tools

if ! SDKMANAGER="$(resolve_cmdline_tool sdkmanager)"; then
  fatal "sdkmanager not found under $ANDROID_HOME/cmdline-tools"
fi
if ! AVDMANAGER="$(resolve_cmdline_tool avdmanager)"; then
  fatal "avdmanager not found under $ANDROID_HOME/cmdline-tools"
fi
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"

CMDLINE_TOOLS_DIR="$(dirname "$SDKMANAGER")"
export PATH="$CMDLINE_TOOLS_DIR:$PATH"
AVDMANAGER_DIR="$(dirname "$AVDMANAGER")"
if [[ "$AVDMANAGER_DIR" != "$CMDLINE_TOOLS_DIR" ]]; then
  export PATH="$AVDMANAGER_DIR:$PATH"
fi

SOURCE_BUCKET="${NOVAPDF_AUTOMATION_SOURCE_BUCKET:-pics-1234}"
SOURCE_KEY="${NOVAPDF_AUTOMATION_SOURCE_KEY:-AI.pdf}"
TARGET_BUCKET="${NOVAPDF_AUTOMATION_TARGET_BUCKET:-novapdfreader}"
AVD_NAME="${NOVAPDF_AUTOMATION_AVD_NAME:-novapdf-automation-api32}"
SYSTEM_IMAGE="${NOVAPDF_AUTOMATION_SYSTEM_IMAGE:-system-images;android-32;google_apis;x86_64}"
DEVICE_PROFILE="${NOVAPDF_AUTOMATION_DEVICE_PROFILE:-pixel_5}"
PLATFORM_API="${NOVAPDF_AUTOMATION_PLATFORM_API:-32}"
RENDER_WAIT="${NOVAPDF_AUTOMATION_RENDER_WAIT:-10}"
RENDER_INITIAL_DELAY="${NOVAPDF_AUTOMATION_INITIAL_DELAY:-5}"
RENDER_TIMEOUT="${NOVAPDF_AUTOMATION_RENDER_TIMEOUT:-120}"
EMULATOR_START_TIMEOUT="${NOVAPDF_AUTOMATION_EMULATOR_START_TIMEOUT:-180}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
ADB_SERIAL="${ADB_SERIAL:-${NOVAPDF_AUTOMATION_SERIAL:-emulator-5554}}"

if ! [[ "$EMULATOR_START_TIMEOUT" =~ ^[0-9]+$ ]] || (( EMULATOR_START_TIMEOUT <= 0 )); then
  fatal "NOVAPDF_AUTOMATION_EMULATOR_START_TIMEOUT must be a positive integer"
fi

export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"
export AWS_EC2_METADATA_DISABLED=true

AWS_ACCESS_KEY_ID_VALUE="${AWS_ACCESS_KEY_ID:-}"
AWS_SECRET_ACCESS_KEY_VALUE="${AWS_SECRET_ACCESS_KEY:-}"
AWS_SESSION_TOKEN_VALUE="${AWS_SESSION_TOKEN:-}"

if [[ -z "$AWS_ACCESS_KEY_ID_VALUE" || -z "$AWS_SECRET_ACCESS_KEY_VALUE" ]]; then
  fatal "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables must be supplied"
fi

WORK_DIR="$(mktemp -d)"
PDF_LOCAL_PATH="$WORK_DIR/AI.pdf"
LOCAL_SCREENSHOT_RAW="$WORK_DIR/device_screencap.png"
LOGCAT_CAPTURE_PATH="$WORK_DIR/logcat.txt"
EMULATOR_LOG="$WORK_DIR/emulator.log"
SHARED_STAGING_PATH="/sdcard/Download/AI.pdf"
EMULATOR_SCREENSHOT_PATH="/sdcard/Download/novapdf_automation.png"
ARTIFACT_NAME="AI_pdf_render_$(date -u +"%Y%m%d_%H%M%S").png"
ARTIFACT_PATH="$WORK_DIR/$ARTIFACT_NAME"
DEVICE_CACHE_DIR="cache/pdf-cache/docs"
DEVICE_CACHE_PATH="$DEVICE_CACHE_DIR/AI.pdf"
CONTENT_URI="content://${PACKAGE_NAME}.fileprovider/pdf_docs/AI.pdf"

EMULATOR_PID=""
LOGCAT_PID=""
AUTOMATION_SUCCESS_PATTERN="AutomationStatus"
AWS_CONFIG_DIR="$WORK_DIR/aws"
AWS_SHARED_CREDENTIALS_FILE="$AWS_CONFIG_DIR/credentials"
AWS_CONFIG_FILE="$AWS_CONFIG_DIR/config"

mkdir -p "$AWS_CONFIG_DIR"
chmod 700 "$AWS_CONFIG_DIR"

{
  old_umask=$(umask)
  umask 077
  cat >"$AWS_SHARED_CREDENTIALS_FILE" <<EOF
[default]
aws_access_key_id = $AWS_ACCESS_KEY_ID_VALUE
aws_secret_access_key = $AWS_SECRET_ACCESS_KEY_VALUE
EOF
  if [[ -n "$AWS_SESSION_TOKEN_VALUE" ]]; then
    cat >>"$AWS_SHARED_CREDENTIALS_FILE" <<EOF
aws_session_token = $AWS_SESSION_TOKEN_VALUE
EOF
  fi
  cat >"$AWS_CONFIG_FILE" <<EOF
[default]
region = $AWS_REGION
output = json
EOF
  umask "$old_umask"
}

export AWS_SHARED_CREDENTIALS_FILE AWS_CONFIG_FILE

log "Configuring AWS CLI credentials for automation"

cleanup() {
  local exit_code=$?
  if [[ -n "$LOGCAT_PID" ]] && kill -0 "$LOGCAT_PID" >/dev/null 2>&1; then
    log "Stopping logcat capture (PID $LOGCAT_PID)"
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" 2>/dev/null || true
  fi
  if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    log "Stopping Android emulator (PID $EMULATOR_PID)"
    adb -s "$ADB_SERIAL" emu kill >/dev/null 2>&1 || true
    wait "$EMULATOR_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
  return $exit_code
}
trap cleanup EXIT INT TERM

log "Ensuring required Android SDK packages are installed"
# sdkmanager may close its stdin early which causes `yes` to receive SIGPIPE.
# With `set -o pipefail` enabled at the top of this script, that would normally
# surface as a pipeline failure even if sdkmanager succeeded. Run those
# pipelines inside a subshell with pipefail disabled so that we only pay
# attention to the exit status of sdkmanager itself.
if ! ( set +o pipefail; yes 2>/dev/null | "$SDKMANAGER" --licenses >/dev/null 2>&1 ); then
  log "Failed to accept Android SDK licenses"
fi
packages=(
  "platform-tools"
  "platforms;android-${PLATFORM_API}"
  "$SYSTEM_IMAGE"
  "emulator"
)
for pkg in "${packages[@]}"; do
  if ! ( set +o pipefail; yes 2>/dev/null | "$SDKMANAGER" "$pkg" >/dev/null ); then
    fatal "Failed to install SDK package $pkg"
  fi
  log "Ensured SDK package $pkg is installed"
done

if [[ ! -x "$EMULATOR_BIN" ]]; then
  fatal "Android emulator binary not found at $EMULATOR_BIN after installing SDK packages"
fi

if ! command -v adb >/dev/null 2>&1; then
  fatal "adb is not available after installing platform-tools"
fi

log "Creating (or updating) AVD $AVD_NAME"
if ! avd_output=$( ( set +o pipefail; echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$DEVICE_PROFILE" --force ) 2>&1 ); then
  log "Failed to create AVD $AVD_NAME. avdmanager output follows:"
  printf '%s\n' "$avd_output" >&2
  fatal "Unable to create AVD $AVD_NAME"
fi

if [[ ! -f "$ANDROID_AVD_HOME/${AVD_NAME}.ini" ]]; then
  log "Expected AVD definition not found at $ANDROID_AVD_HOME/${AVD_NAME}.ini"
  if [[ -n "${avd_output:-}" ]]; then
    log "avdmanager output:"
    printf '%s\n' "$avd_output" >&2
  fi
  fatal "AVD $AVD_NAME was not created successfully"
fi

log "AVD $AVD_NAME is ready under $ANDROID_AVD_HOME"

log "Downloading PDF s3://${SOURCE_BUCKET}/${SOURCE_KEY}"
aws s3 cp "s3://${SOURCE_BUCKET}/${SOURCE_KEY}" "$PDF_LOCAL_PATH" --only-show-errors || fatal "Failed to download PDF from S3"

check_kvm_support

log "Starting Android emulator $AVD_NAME (logging to $EMULATOR_LOG)"
"$EMULATOR_BIN" -avd "$AVD_NAME" -no-snapshot -no-window -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none -netfast -wipe-data >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

log "Waiting for emulator (serial $ADB_SERIAL) to appear (timeout ${EMULATOR_START_TIMEOUT}s)"
start_elapsed=0
while (( start_elapsed < EMULATOR_START_TIMEOUT )); do
  if adb -s "$ADB_SERIAL" get-state 2>/dev/null | grep -q '^device$'; then
    break
  fi
  check_emulator_health "$EMULATOR_PID" "$EMULATOR_LOG"
  sleep 2
  start_elapsed=$((start_elapsed + 2))
done

if (( start_elapsed >= EMULATOR_START_TIMEOUT )); then
  check_emulator_health "$EMULATOR_PID" "$EMULATOR_LOG"
  if [[ -f "$EMULATOR_LOG" ]]; then
    tail -n 200 "$EMULATOR_LOG" >&2 || true
  fi
  fatal "Timed out waiting for emulator to appear on adb within ${EMULATOR_START_TIMEOUT}s"
fi

boot_deadline=$((20 * 60))
elapsed=0
while true; do
  check_emulator_health "$EMULATOR_PID" "$EMULATOR_LOG"
  if adb -s "$ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
    break
  fi
  if (( elapsed >= boot_deadline )); then
    fatal "Emulator did not report boot completion within ${boot_deadline}s"
  fi
  sleep 5
  elapsed=$((elapsed + 5))
done

log "Emulator reported boot completion"

log "Installing APK $APK_PATH"
if ! adb -s "$ADB_SERIAL" install -r "$APK_PATH" >/dev/null 2>&1; then
  log "Streaming install failed; retrying with --no-streaming"
  adb -s "$ADB_SERIAL" install --no-streaming -r "$APK_PATH" >/dev/null 2>&1 || fatal "Unable to install APK"
fi

log "Pushing PDF to shared storage staging path $SHARED_STAGING_PATH"
adb -s "$ADB_SERIAL" push "$PDF_LOCAL_PATH" "$SHARED_STAGING_PATH" >/dev/null || fatal "Failed to push PDF to shared storage"

log "Staging PDF into application cache"
adb -s "$ADB_SERIAL" shell run-as "$PACKAGE_NAME" sh -c "set -e; mkdir -p '$DEVICE_CACHE_DIR'; cat > '$DEVICE_CACHE_PATH'" < "$PDF_LOCAL_PATH" || fatal "Failed to stage PDF"

log "Clearing logcat buffer"
adb -s "$ADB_SERIAL" logcat -c >/dev/null 2>&1 || true
touch "$LOGCAT_CAPTURE_PATH"
log "Starting logcat capture for automation validation"
stdbuf -oL -eL adb -s "$ADB_SERIAL" logcat -v threadtime >"$LOGCAT_CAPTURE_PATH" &
LOGCAT_PID=$!

log "Launching NovaPDFReader via automation intent"
adb -s "$ADB_SERIAL" shell am start -n "${PACKAGE_NAME}/.MainActivity" \
  -a "com.novapdf.reader.action.VIEW_LOCAL_DOCUMENT" \
  --es "com.novapdf.reader.extra.DOCUMENT_URI" "$CONTENT_URI" \
  --grant-read-uri-permission >/dev/null || fatal "Failed to launch application"

log "Waiting ${RENDER_INITIAL_DELAY}s initial delay before monitoring render status"
sleep "$RENDER_INITIAL_DELAY"

log "Monitoring logcat for successful PDF render (timeout ${RENDER_TIMEOUT}s)"
deadline=$((RENDER_TIMEOUT))
elapsed=0
success_detected=false
while (( elapsed < deadline )); do
  if grep -q "${AUTOMATION_SUCCESS_PATTERN}.*PDF automation render complete" "$LOGCAT_CAPTURE_PATH"; then
    success_detected=true
    break
  fi
  if grep -qi "FATAL EXCEPTION" "$LOGCAT_CAPTURE_PATH"; then
    tail -n 200 "$LOGCAT_CAPTURE_PATH" >&2 || true
    fatal "Fatal exception detected in logcat while waiting for PDF render"
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done

if [[ "$success_detected" != true ]]; then
  tail -n 200 "$LOGCAT_CAPTURE_PATH" >&2 || true
  fatal "Timed out waiting for PDF render confirmation in logcat"
fi

log "Render confirmation detected; waiting additional ${RENDER_WAIT}s before capture"
sleep "$RENDER_WAIT"

log "Capturing emulator screenshot"
adb -s "$ADB_SERIAL" shell rm -f "$EMULATOR_SCREENSHOT_PATH" >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" shell screencap -p "$EMULATOR_SCREENSHOT_PATH" >/dev/null || fatal "Failed to capture screenshot"
adb -s "$ADB_SERIAL" pull "$EMULATOR_SCREENSHOT_PATH" "$LOCAL_SCREENSHOT_RAW" >/dev/null || fatal "Failed to pull screenshot"
adb -s "$ADB_SERIAL" shell rm -f "$EMULATOR_SCREENSHOT_PATH" >/dev/null 2>&1 || true

mv "$LOCAL_SCREENSHOT_RAW" "$ARTIFACT_PATH"

log "Uploading screenshot artifact to s3://${TARGET_BUCKET}/${ARTIFACT_NAME}"
aws s3 cp "$ARTIFACT_PATH" "s3://${TARGET_BUCKET}/${ARTIFACT_NAME}" --only-show-errors || fatal "Failed to upload screenshot to S3"

log "Automation completed successfully. Artifact URI: s3://${TARGET_BUCKET}/${ARTIFACT_NAME}"
exit 0
