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

usage() {
  cat <<'USAGE' >&2
Usage: automated_pdf_render.sh --apk <path> --package <name> [--serial <serial>]

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
  AWS_REGION / AWS_DEFAULT_REGION    AWS region (default: us-east-1)
USAGE
}

APK_PATH=""
PACKAGE_NAME=""
ADB_SERIAL=""

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
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fatal "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$APK_PATH" ]] || fatal "--apk path is required"
[[ -n "$PACKAGE_NAME" ]] || fatal "--package is required"

if [[ ! -f "$APK_PATH" ]]; then
  fatal "APK path $APK_PATH does not exist"
fi

if ! command -v aws >/dev/null 2>&1; then
  fatal "aws CLI is not installed"
fi

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$ANDROID_HOME" ]] || fatal "ANDROID_HOME or ANDROID_SDK_ROOT must be set"

export PATH="$ANDROID_HOME/platform-tools:$PATH"

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
[[ -x "$EMULATOR_BIN" ]] || fatal "Android emulator binary not found at $EMULATOR_BIN"

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
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
ADB_SERIAL="${ADB_SERIAL:-${NOVAPDF_AUTOMATION_SERIAL:-emulator-5554}}"

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
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
packages=(
  "platform-tools"
  "platforms;android-${PLATFORM_API}"
  "$SYSTEM_IMAGE"
  "emulator"
)
for pkg in "${packages[@]}"; do
  yes | "$SDKMANAGER" "$pkg" >/dev/null || fatal "Failed to install SDK package $pkg"
done

if ! command -v adb >/dev/null 2>&1; then
  fatal "adb is not available after installing platform-tools"
fi

echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$DEVICE_PROFILE" --force >/dev/null 2>&1 || true

log "Downloading PDF s3://${SOURCE_BUCKET}/${SOURCE_KEY}"
aws s3 cp "s3://${SOURCE_BUCKET}/${SOURCE_KEY}" "$PDF_LOCAL_PATH" --only-show-errors || fatal "Failed to download PDF from S3"

log "Starting Android emulator $AVD_NAME"
"$EMULATOR_BIN" -avd "$AVD_NAME" -no-snapshot -no-window -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none -netfast -wipe-data &
EMULATOR_PID=$!

log "Waiting for emulator (serial $ADB_SERIAL) to appear"
adb -s "$ADB_SERIAL" wait-for-device >/dev/null

boot_deadline=$((20 * 60))
elapsed=0
while true; do
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
