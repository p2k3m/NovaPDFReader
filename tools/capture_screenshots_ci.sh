#!/usr/bin/env bash
set -euo pipefail

if [ "${NOVAPDF_VIRTUALIZATION_UNAVAILABLE:-}" = "true" ]; then
  echo "::warning::Skipping screenshot capture because Android emulator virtualization is unavailable" >&2
  exit 0
fi

resource_dir="${ARTIFACTS_DIR}/resources/api${MATRIX_API}/${MATRIX_DEVICE_LABEL}"
mkdir -p "$resource_dir"

resource_snapshot() {
  local label="$1"
  local path="$2"
  shift 2 || true
  python3 tools/device_resource_snapshot.py --label "$label" --output "$path" "$@"
}

base_dir="${ARTIFACTS_DIR}/screenshots/api${MATRIX_API}/${MATRIX_DEVICE_LABEL}"
mkdir -p "$base_dir"

READY_FLAG_BASENAME="screenshot_ready.flag"
DONE_FLAG_BASENAME="screenshot_done.flag"
HARNESS_CLASS="com.novapdf.reader.ScreenshotHarnessTest#openThousandPageDocumentForScreenshots"
HARNESS_LOG="$base_dir/screenshot-harness.log"
HARNESS_LOGCAT="$base_dir/screenshot-harness-logcat.log"
PERFORMANCE_DIR="${ARTIFACTS_DIR}/performance/api${MATRIX_API}/${MATRIX_DEVICE_LABEL}"
METRICS_FILE_NAME="performance_metrics.csv"

before_snapshot_path="${resource_dir}/screenshot-harness-before.json"
after_snapshot_path="${resource_dir}/screenshot-harness-after.json"
captured_after_snapshot=0

resource_snapshot "screenshot-harness-before" "$before_snapshot_path" --include-properties

compute_timeout_from_snapshot() {
  local snapshot_path="$1"
  local base_seconds="$2"
  python3 -c $'import json, sys\npath = sys.argv[1]\nbase = float(sys.argv[2])\nwith open(path, "r", encoding="utf-8") as handle:\n    data = json.load(handle)\n\ntotal_kb = float(data.get("mem_total_kb") or 0)\navailable_percent = float(data.get("mem_available_percent") or 0)\ncpu = float(data.get("cpu_usage_percent") or 0)\ncores = float(data.get("cpu_cores") or 0)\n\nscale = 1.0\nif total_kb > 0:\n    mem_gb = total_kb / (1024.0 * 1024.0)\n    if mem_gb < 2.0:\n        scale *= 2.8\n    elif mem_gb < 4.0:\n        scale *= 1.9\n    elif mem_gb < 6.0:\n        scale *= 1.4\n    elif mem_gb > 8.0:\n        scale *= 0.9\n    else:\n        scale *= 1.1\n\nif cores:\n    if cores <= 4:\n        scale *= 1.3\n    elif cores >= 8:\n        scale *= 0.9\n\nif available_percent and available_percent < 15.0:\n    scale *= 1.2\n\nif cpu and cpu > 85.0:\n    scale *= 1.15\n\ntimeout = base * scale\ntimeout = max(240.0, min(timeout, 1200.0))\nprint(int(round(timeout)))\n' "$snapshot_path" "$base_seconds"
}

ACTIVITY_MANAGER_TIMEOUT=$(compute_timeout_from_snapshot "$before_snapshot_path" 300)
HARNESS_READY_TIMEOUT=$(compute_timeout_from_snapshot "$before_snapshot_path" 360)
echo "Using dynamic timeouts for screenshot harness: activity manager ${ACTIVITY_MANAGER_TIMEOUT}s, readiness ${HARNESS_READY_TIMEOUT}s" >&2

HARNESS_RUN_AS_PACKAGE="${PACKAGE_NAME}.test"
APP_RUN_AS_PACKAGE="${PACKAGE_NAME}"
HARNESS_RUNNER="dagger.hilt.android.testing.HiltTestRunner"
HARNESS_COMPONENT=""
HARNESS_FLAG_PACKAGE=""

HANDSHAKE_PACKAGES=()
HANDSHAKE_DIRECTORY_HINTS=()

resolve_package_name() {
  local candidate="$1"
  if [ -z "$candidate" ]; then
    return 1
  fi

  candidate=$(printf '%s' "$candidate" | tr -d '\r\n')
  if [ -z "$candidate" ]; then
    return 1
  fi

  if [[ "$candidate" == *"*"* ]]; then
    local normalized
    normalized=$(python3 - "$candidate" <<'PY' 2>/dev/null
    import re
    import subprocess
    import sys

    pattern = sys.argv[1]

    try:
        result = subprocess.run(
            ["adb", "shell", "pm", "list", "packages"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        sys.exit(1)

    regex = re.compile("^" + re.escape(pattern).replace("\\*", ".*") + "$")
    matches = []
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if not stripped.startswith("package:"):
            continue
        package = stripped.split(":", 1)[1].strip()
        if regex.match(package):
            matches.append(package)

    if not matches:
        sys.exit(1)

    if len(matches) == 1:
        print(matches[0])
        sys.exit(0)

    suffix = pattern.split("*")[-1]
    if suffix:
        for match in matches:
            if match.endswith(suffix):
                print(match)
                sys.exit(0)

    print(matches[0])
PY
    )
    normalized=$(printf '%s' "$normalized" | tr -d '\r\n')
    if [ -n "$normalized" ]; then
      printf '%s' "$normalized"
      return 0
    fi
  fi

  printf '%s' "$candidate"
  return 0
}

add_handshake_directory_hint() {
  local entry="$1"
  if [ -z "$entry" ]; then
    return
  fi

  local existing
  for existing in "${HANDSHAKE_DIRECTORY_HINTS[@]}"; do
    if [ "$existing" = "$entry" ]; then
      return
    fi
  done

  HANDSHAKE_DIRECTORY_HINTS+=("$entry")
}

add_handshake_package() {
  local candidate="$1"
  if [ -z "$candidate" ]; then
    return
  fi

  local normalized=""
  if ! normalized=$(resolve_package_name "$candidate" 2>/dev/null); then
    normalized="$candidate"
  fi

  if [ -z "$normalized" ]; then
    return
  fi

  for existing in "${HANDSHAKE_PACKAGES[@]}"; do
    if [ "$existing" = "$normalized" ]; then
      normalized=""
      break
    fi
  done

  if [ -n "$normalized" ]; then
    HANDSHAKE_PACKAGES+=("$normalized")
  fi

  if [ "$candidate" != "$normalized" ]; then
    for existing in "${HANDSHAKE_PACKAGES[@]}"; do
      if [ "$existing" = "$candidate" ]; then
        return
      fi
    done
    HANDSHAKE_PACKAGES+=("$candidate")
  fi
}

refresh_handshake_packages() {
  HANDSHAKE_PACKAGES=()
  add_handshake_package "$HARNESS_RUN_AS_PACKAGE"
  local sanitized="${HARNESS_RUN_AS_PACKAGE//\*/}"
  if [ -n "$sanitized" ]; then
    add_handshake_package "$sanitized"
  fi
  local normalized=""
  if normalized=$(resolve_package_name "$HARNESS_RUN_AS_PACKAGE" 2>/dev/null); then
    if [ -n "$normalized" ] && [ "$normalized" != "$HARNESS_RUN_AS_PACKAGE" ]; then
      add_handshake_package "$normalized"
    fi
  fi
  if [ "$APP_RUN_AS_PACKAGE" != "$HARNESS_RUN_AS_PACKAGE" ]; then
    add_handshake_package "$APP_RUN_AS_PACKAGE"
  fi
}

refresh_handshake_packages
HARNESS_FLAG_PACKAGE="$HARNESS_RUN_AS_PACKAGE"
HARNESS_FLAG_RELATIVE_DIR=""

list_flag_paths() {
  local package="$1"
  local basename="$2"
  if [ -z "$package" ] || [ -z "$basename" ]; then
    return
  fi

  local candidates=("cache/$basename")
  local harness_directories=(
    "screenshot-harness"
    "screenshot_harness"
  )
  local harness_directory
  for harness_directory in "${harness_directories[@]}"; do
    candidates+=(
      "cache/$harness_directory/$basename"
      "code_cache/$harness_directory/$basename"
      "files/$harness_directory/$basename"
      "no_backup/$harness_directory/$basename"
    )
  done
  local hint
  for hint in "${HANDSHAKE_DIRECTORY_HINTS[@]}"; do
    local hint_package="${hint%%:*}"
    local hint_directory="${hint#*:}"
    if [ -z "$hint_package" ] || [ "$hint_package" != "$package" ]; then
      continue
    fi
    if [ -z "$hint_directory" ]; then
      continue
    fi
    hint_directory="${hint_directory%/}"
    if [ -z "$hint_directory" ]; then
      continue
    fi
    candidates+=("$hint_directory/$basename")
  done

  printf '%s\n' "${candidates[@]}" | awk 'NF' | awk '!seen[$0]++'
}

update_handshake_directory_hints() {
  if [ ! -f "$HARNESS_LOG" ]; then
    return
  fi

  local parsed
  parsed=$(
    python3 - "$HARNESS_LOG" "${HANDSHAKE_PACKAGES[@]}" <<'PY'
import os
import re
import sys

log_path = sys.argv[1]
packages = [arg for arg in sys.argv[2:] if arg]
package_set = set(packages)

try:
    with open(log_path, "r", encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
except OSError:
    sys.exit(0)

patterns = [
    re.compile(r"Using handshake cache directories (?P<paths>.+)", re.IGNORECASE),
    re.compile(r"Resolved screenshot handshake cache directories (?P<paths>.+)", re.IGNORECASE),
]

def parse_paths(segment):
    if " for package " in segment:
        paths_part, _ = segment.split(" for package ", 1)
    else:
        paths_part = segment
    values = []
    for candidate in paths_part.split(","):
        value = candidate.strip()
        if not value:
            continue
        if " |" in value:
            value = value.split(" |", 1)[0].strip()
        if value.endswith(")") and "(" in value:
            value = value[: value.rfind("(")].strip()
        values.append(value)
    return values

def extract_relative_path(path, package):
    prefixes = [
        f"/data/user/0/{package}/",
        f"/data/user_de/0/{package}/",
        f"/data/data/{package}/",
    ]
    for prefix in prefixes:
        if path.startswith(prefix):
            relative = path[len(prefix):].lstrip("/")
            if relative:
                return relative
    return None

def infer_package_from_path(path):
    match = re.search(r"/data/(?:user(?:_de)?/\d+/|data/)([^/]+)/(.+)", path)
    if match:
        package = match.group(1)
        relative = match.group(2).lstrip("/")
        if relative:
            return package, relative
    return None, None

results = []
for raw_line in lines:
    line = raw_line.strip()
    if not line:
        continue
    if "handshake cache directories" not in line:
        continue
    if "|" in line:
        line = line.split("|", 1)[0].strip()
    match = None
    path_package = None
    for pattern in patterns:
        match = pattern.search(line)
        if match:
            break
    if not match:
        continue
    segment = match.group("paths").strip()
    if " for package " in segment:
        segment, package_segment = segment.split(" for package ", 1)
        path_package = package_segment.strip().split()[0]
    for path in parse_paths(segment):
        if not path:
            continue
        inferred_package, inferred_relative = infer_package_from_path(path)
        added = False
        if inferred_package and inferred_relative:
            if not package_set or inferred_package in package_set:
                results.append(f"{inferred_package}:{inferred_relative}")
                added = True
        if added:
            continue
        if path_package:
            if not package_set or path_package in package_set:
                relative = extract_relative_path(path, path_package)
                if relative:
                    results.append(f"{path_package}:{relative}")

if results:
    seen = set()
    for entry in results:
        if entry in seen:
            continue
        seen.add(entry)
        print(entry)
PY
  ) || return
  if [ -z "$parsed" ]; then
    return
  fi

  local line
  while IFS= read -r line; do
    add_handshake_directory_hint "$line"
  done <<< "$parsed"
}

resolve_instrumentation_component() {
  local package_name="$1"
  local runner_name="$2"

  local listing
  if ! listing=$(adb shell pm list instrumentation 2>/dev/null | tr -d '\r'); then
    return 1
  fi

  local python_script
  python_script=$'import re\nimport sys\n\npackage_name = sys.argv[1]\nrunner_name = sys.argv[2]\nlines = sys.stdin.read().splitlines()\n\ndefault_component = ""\nif package_name:\n    default_component = f"{package_name}.test/{runner_name}"\n\ncandidates = []\nfor line in lines:\n    stripped = line.strip()\n    if not stripped.startswith("instrumentation:"):\n        continue\n    component = stripped[len("instrumentation:"):].split(" ", 1)[0].strip()\n    target = ""\n    if "(target=" in stripped:\n        start = stripped.find("(target=") + len("(target=")\n        end = stripped.find(")", start)\n        if end == -1:\n            end = len(stripped)\n        target = stripped[start:end]\n    candidates.append((component, target))\n\nif default_component:\n    for component, _target in candidates:\n        if component == default_component:\n            print(component)\n            sys.exit(0)\n\nfor component, target in candidates:\n    if package_name and target == package_name and component.endswith(f"/{runner_name}"):\n        print(component)\n        sys.exit(0)\n\nif package_name:\n    sanitized = re.sub(r"\\*+", "", package_name)\n    if sanitized and sanitized != package_name:\n        sanitized_default = f"{sanitized}.test/{runner_name}"\n        for component, _target in candidates:\n            if component == sanitized_default:\n                print(component)\n                sys.exit(0)\n        for component, target in candidates:\n            if target == sanitized and component.endswith(f"/{runner_name}"):\n                print(component)\n                sys.exit(0)\n\nfor component, _target in candidates:\n    if component.endswith(f"/{runner_name}"):\n        print(component)\n        sys.exit(0)\n\nif candidates:\n    print(candidates[0][0])\n'
  local resolved
  resolved=$(printf '%s\n' "$listing" | python3 -c "$python_script" "$package_name" "$runner_name") || return 1

  resolved=$(printf '%s' "$resolved" | tr -d '\r\n')
  if [ -n "$resolved" ]; then
    HARNESS_COMPONENT="$resolved"
    HARNESS_RUN_AS_PACKAGE="${resolved%%/*}"
    refresh_handshake_packages
    HARNESS_FLAG_PACKAGE="$HARNESS_RUN_AS_PACKAGE"
    update_handshake_directory_hints
    return 0
  fi

  return 1
}

wait_for_instrumentation_component() {
  local package_name="$1"
  local runner_name="$2"
  local attempts="${3:-20}"
  local delay_seconds="${4:-3}"
  local sanitized_package="${package_name//\*/}"

  while [ "$attempts" -gt 0 ]; do
    local listing
    if listing=$(adb shell pm list instrumentation 2>/dev/null | tr -d '\r'); then
        local resolved
        resolved=$(printf '%s\n' "$listing" | python3 -c $'import sys\n\nrequested = sys.argv[1]\nsanitized = sys.argv[2]\nrunner = sys.argv[3]\n\nlines = [line.strip() for line in sys.stdin.read().splitlines() if line.strip().startswith("instrumentation:")]\ncomponents = []\nfor line in lines:\n    component = line[len("instrumentation:"):].split(" ", 1)[0].strip()\n    target = ""\n    if "(target=" in line:\n        start = line.find("(target=") + len("(target=")\n        end = line.find(")", start)\n        if end == -1:\n            end = len(line)\n        target = line[start:end]\n    components.append((component, target))\n\ndef matches_package(package_candidate: str) -> bool:\n    if not package_candidate:\n        return False\n    if package_candidate == requested:\n        return True\n    if sanitized and package_candidate == sanitized:\n        return True\n    return False\n\nfor component, target in components:\n    pkg, _, comp_runner = component.partition("/")\n    if matches_package(pkg):\n        if not runner or comp_runner == runner:\n            print(component)\n            sys.exit(0)\n\nfor component, target in components:\n    if matches_package(target):\n        if not runner or component.rsplit("/", 1)[-1] == runner:\n            print(component)\n            sys.exit(0)\n\nsys.exit(1)\n' "$package_name" "$sanitized_package" "$runner_name")
      resolved=$(printf '%s' "$resolved" | tr -d '\r\n')
      if [ -n "$resolved" ]; then
        echo "Confirmed availability of instrumentation component $resolved"
        return 0
      fi
    fi

    attempts=$((attempts - 1))
    if [ "$attempts" -le 0 ]; then
      break
    fi
    sleep "$delay_seconds"
  done

  return 1
}

cleanup_flags() {
  update_handshake_directory_hints
  for package in "${HANDSHAKE_PACKAGES[@]}"; do
    if [ -z "$package" ]; then
      continue
    fi
    local ready_paths
    ready_paths=$(list_flag_paths "$package" "$READY_FLAG_BASENAME")
    if [ -n "$ready_paths" ]; then
      while IFS= read -r path; do
        [ -z "$path" ] && continue
        adb shell run-as "$package" sh -c "rm -f '$path'" >/dev/null 2>&1 || true
      done <<< "$ready_paths"
    fi
    local done_paths
    done_paths=$(list_flag_paths "$package" "$DONE_FLAG_BASENAME")
    if [ -n "$done_paths" ]; then
      while IFS= read -r path; do
        [ -z "$path" ] && continue
        adb shell run-as "$package" sh -c "rm -f '$path'" >/dev/null 2>&1 || true
      done <<< "$done_paths"
    fi
  done
}

harness_pid=""
HARNESS_EXIT_REASON=""
MAX_SYSTEM_CRASH_RETRIES=1
rm -f "$HARNESS_LOGCAT"
collect_harness_logcat() {
  rm -f "$HARNESS_LOGCAT"
  if adb logcat -d > "$HARNESS_LOGCAT"; then
    echo "Collected screenshot harness logcat at $HARNESS_LOGCAT" >&2
    if [ -s "$HARNESS_LOGCAT" ]; then
      echo "---- screenshot harness logcat (tail) ----" >&2
      tail -n 200 "$HARNESS_LOGCAT" >&2 || true
      echo "-----------------------------------------" >&2
    fi
  else
    echo "Failed to capture screenshot harness logcat" >&2
  fi
  adb logcat -c >/dev/null 2>&1 || true
}
collect_native_crash_artifacts() {
  local native_dir="${base_dir}/native-crash"

  if ! tools/collect_native_crash_artifacts.sh "$native_dir"; then
    echo "Failed to collect native crash artifacts" >&2
  fi
}

collect_app_crash_logs() {
  local crash_dir="crashlogs"
  local output_dir="${base_dir}/crashlogs"
  mkdir -p "$output_dir"

  local listing
  if ! listing=$(adb shell run-as "$APP_RUN_AS_PACKAGE" ls -1 "$crash_dir" 2>/dev/null | tr -d '\r'); then
    echo "Crash log directory unavailable for $APP_RUN_AS_PACKAGE" >&2
    return
  fi

  listing=$(printf '%s\n' "$listing" | awk 'NF')
  if [ -z "$listing" ]; then
    echo "No crash logs captured for $APP_RUN_AS_PACKAGE" >&2
    return
  fi

  echo "Collecting crash logs from $APP_RUN_AS_PACKAGE/$crash_dir" >&2
  while IFS= read -r entry; do
    local remote_path="$crash_dir/$entry"
    local local_path="$output_dir/$entry"
    if adb shell run-as "$APP_RUN_AS_PACKAGE" cat "$remote_path" > "$local_path"; then
      echo "Saved crash log to $local_path" >&2
      tail -n 40 "$local_path" >&2 || true
    else
      echo "Failed to export crash log $remote_path" >&2
    fi
  done <<< "$listing"

  collect_native_crash_artifacts
}
collect_performance_metrics() {
  local package="$1"
  local label="$2"

  if [ -z "$package" ]; then
    return
  fi

  if ! adb shell run-as "$package" sh -c "exit 0" >/dev/null 2>&1; then
    echo "run-as unavailable for $package; skipping performance metrics collection" >&2
    return
  fi

  local remote_files
  if ! remote_files=$(adb shell run-as "$package" sh -c "find . -maxdepth 5 -type f -name '$METRICS_FILE_NAME' 2>/dev/null" | tr -d '\r'); then
    echo "Failed to query performance metrics paths for $package" >&2
    return
  fi

  remote_files=$(printf '%s\n' "$remote_files" | awk 'NF')
  if [ -z "$remote_files" ]; then
    echo "No performance metrics located for $package" >&2
    return
  fi

  mkdir -p "$PERFORMANCE_DIR"
  while IFS= read -r remote_path; do
    remote_path=${remote_path#./}
    if [ -z "$remote_path" ]; then
      continue
    fi
    local sanitized="${remote_path//\//_}"
    local dest="$PERFORMANCE_DIR/${label}-${sanitized}"
    if adb shell run-as "$package" sh -c "cat '$remote_path'" > "$dest"; then
      echo "Exported performance metrics from $package:$remote_path to $dest" >&2
    else
      echo "Failed to export performance metrics from $package:$remote_path" >&2
      rm -f "$dest"
    fi
  done <<< "$remote_files"
}
resolve_harness_run_as_package() {
  local timeout=$((2 * 60))
  local elapsed=0
  local resolved=""
  local previous="$HARNESS_RUN_AS_PACKAGE"

  while [ $elapsed -lt $timeout ]; do
      if [ -f "$HARNESS_LOG" ]; then
        resolved=$(python3 -c $'import sys\n\nif len(sys.argv) < 2:\n    sys.exit(0)\n\npath = sys.argv[1]\ntarget = "ScreenshotHarness: Resolved screenshot harness package name:"\npackage = ""\n\ntry:\n    with open(path, "r", encoding="utf-8", errors="replace") as handle:\n        for line in handle:\n            if target in line:\n                candidate = line.split(target, 1)[1].strip()\n                if candidate:\n                    package = candidate.split()[0]\nexcept Exception:\n    package = ""\n\nif package:\n    sys.stdout.write(package)\n' "$HARNESS_LOG")
      resolved=$(printf '%s' "$resolved" | tr -d '\r\n')
      if [ -n "$resolved" ]; then
        if [ "$resolved" != "$HARNESS_RUN_AS_PACKAGE" ]; then
          local normalized=""
          if normalized=$(resolve_package_name "$resolved" 2>/dev/null); then
            if [ -n "$normalized" ]; then
              if [ "$normalized" != "$resolved" ]; then
                echo "Resolved sanitized screenshot harness package $resolved to $normalized" >&2
              fi
              resolved="$normalized"
            fi
          fi
          HARNESS_RUN_AS_PACKAGE="$resolved"
          refresh_handshake_packages
          HARNESS_FLAG_PACKAGE="$HARNESS_RUN_AS_PACKAGE"
          update_handshake_directory_hints
          echo "Updated screenshot harness run-as package to $HARNESS_RUN_AS_PACKAGE" >&2
          if [ "$previous" != "$HARNESS_RUN_AS_PACKAGE" ]; then
            cleanup_flags
          fi
        fi
        return 0
      fi
    fi

    if ! kill -0 "$harness_pid" >/dev/null 2>&1; then
      break
    fi

    sleep 1
    elapsed=$((elapsed + 1))
  done

  echo "Unable to determine screenshot harness package from instrumentation output; continuing with $HARNESS_RUN_AS_PACKAGE" >&2
  return 1
}
finish_harness() {
  if [ -n "${harness_pid:-}" ] && kill -0 "$harness_pid" >/dev/null 2>&1; then
    echo "Stopping screenshot harness instrumentation" >&2
    kill "$harness_pid" >/dev/null 2>&1 || true
    wait "$harness_pid" >/dev/null 2>&1 || true
  fi
  if [ "${captured_after_snapshot}" -eq 0 ]; then
    captured_after_snapshot=1
    resource_snapshot "screenshot-harness-after" "$after_snapshot_path"
  fi
  local metrics_package="$HARNESS_FLAG_PACKAGE"
  if [ -z "$metrics_package" ]; then
    metrics_package="$HARNESS_RUN_AS_PACKAGE"
  fi
  collect_performance_metrics "$metrics_package" "harness"
  collect_performance_metrics "$APP_RUN_AS_PACKAGE" "app"
  cleanup_flags
}
trap finish_harness EXIT

wait_for_activity_manager() {
  local elapsed=0
  local timeout=${ACTIVITY_MANAGER_TIMEOUT:-300}
  while true; do
    if adb wait-for-device >/dev/null 2>&1; then
      local status
      status=$(adb shell service check activity 2>/dev/null | tr -d '\r' | tr -d '\n')
      if printf '%s\n' "$status" | grep -qE ': found$'; then
        return 0
      fi
    fi

    if [ $elapsed -ge $timeout ]; then
      echo "::error::Timed out waiting for Activity Manager service before launching screenshot harness (timeout ${timeout}s)" >&2
      collect_harness_logcat
      collect_app_crash_logs
      exit 1
    fi

    sleep 5
    elapsed=$((elapsed + 5))
  done
}

wait_for_activity_manager

if ! wait_for_instrumentation_component "$HARNESS_RUN_AS_PACKAGE" "$HARNESS_RUNNER" 20 3; then
  echo "::error::Screenshot harness instrumentation ${HARNESS_RUN_AS_PACKAGE}/${HARNESS_RUNNER} not detected on device" >&2
  adb shell pm list instrumentation 2>/dev/null || true
  exit 1
fi

if resolve_instrumentation_component "$PACKAGE_NAME" "$HARNESS_RUNNER"; then
  echo "Resolved screenshot harness instrumentation component to $HARNESS_COMPONENT" >&2
else
  HARNESS_COMPONENT="${HARNESS_RUN_AS_PACKAGE}/${HARNESS_RUNNER}"
  echo "Unable to resolve screenshot harness instrumentation component; defaulting to $HARNESS_COMPONENT" >&2
fi

launch_screenshot_harness() {
  echo "Launching screenshot harness instrumentation to load thousand-page PDF"
  HARNESS_FLAG_RELATIVE_DIR=""
  adb logcat -c >/dev/null 2>&1 || true
  adb shell am instrument -w -r \
    -e runScreenshotHarness true \
    -e class "$HARNESS_CLASS" \
    "$HARNESS_COMPONENT" \
    >"$HARNESS_LOG" 2>&1 &
  harness_pid=$!
  HARNESS_EXIT_REASON=""
  resolve_harness_run_as_package || true
}

wait_for_harness() {
  local elapsed=0
  local timeout=${HARNESS_READY_TIMEOUT:-360}
  HARNESS_EXIT_REASON=""
  while true; do
    update_handshake_directory_hints
    if ! kill -0 "$harness_pid" >/dev/null 2>&1; then
      echo "::error::Screenshot harness instrumentation exited before reporting readiness" >&2
      cat "$HARNESS_LOG" >&2 || true
      HARNESS_EXIT_REASON="unexpected_exit"
      if [ -f "$HARNESS_LOG" ] && grep -q "System has crashed" "$HARNESS_LOG"; then
        HARNESS_EXIT_REASON="system_crash"
      fi
      collect_harness_logcat
      collect_app_crash_logs
      return 1
    fi

    local ready_package=""
    local ready_relative_path=""
    for package in "${HANDSHAKE_PACKAGES[@]}"; do
      if [ -z "$package" ]; then
        continue
      fi
      local candidate_paths
      candidate_paths=$(list_flag_paths "$package" "$READY_FLAG_BASENAME")
      if [ -z "$candidate_paths" ]; then
        continue
      fi
      local path
      while IFS= read -r path; do
        [ -z "$path" ] && continue
        if adb shell run-as "$package" sh -c "[ -f '$path' ]" >/dev/null 2>&1; then
          ready_package="$package"
          ready_relative_path="$path"
          break
        fi
      done <<< "$candidate_paths"
      if [ -n "$ready_package" ]; then
        break
      fi
    done

    if [ -n "$ready_package" ]; then
      HARNESS_FLAG_PACKAGE="$ready_package"
      if [ -n "$ready_relative_path" ]; then
        if [[ "$ready_relative_path" == */* ]]; then
          HARNESS_FLAG_RELATIVE_DIR="${ready_relative_path%/*}"
        else
          HARNESS_FLAG_RELATIVE_DIR="."
        fi
      fi
      echo "Detected screenshot readiness flag for package $HARNESS_FLAG_PACKAGE" >&2
      break
    fi

    if [ $elapsed -ge $timeout ]; then
      echo "::error::Timed out waiting for screenshot harness readiness flag (timeout ${timeout}s)" >&2
      cat "$HARNESS_LOG" >&2 || true
      HARNESS_EXIT_REASON="readiness_timeout"
      collect_harness_logcat
      collect_app_crash_logs
      return 1
    fi

    sleep 2
    elapsed=$((elapsed + 2))
  done
  HARNESS_EXIT_REASON=""
  return 0
}

signal_screenshot_completion() {
  local package="$1"
  if [ -z "$package" ]; then
    return 1
  fi

  local candidates=()
  if [ -n "$HARNESS_FLAG_RELATIVE_DIR" ]; then
    if [ "$HARNESS_FLAG_RELATIVE_DIR" = "." ]; then
      candidates+=("$DONE_FLAG_BASENAME")
    else
      candidates+=("${HARNESS_FLAG_RELATIVE_DIR%/}/$DONE_FLAG_BASENAME")
    fi
  fi

  local fallback
  fallback=$(list_flag_paths "$package" "$DONE_FLAG_BASENAME")
  if [ -n "$fallback" ]; then
    while IFS= read -r path; do
      [ -z "$path" ] && continue
      candidates+=("$path")
    done <<< "$fallback"
  fi

  if [ ${#candidates[@]} -eq 0 ]; then
    candidates+=("cache/$DONE_FLAG_BASENAME")
  fi

  local seen=""
  local path
  for path in "${candidates[@]}"; do
    if [ -z "$path" ]; then
      continue
    fi
    if printf '%s\n' "$seen" | grep -qxF "$path"; then
      continue
    fi
    seen+="$path\n"
    local command
    if [[ "$path" == */* ]]; then
      local dir="${path%/*}"
      command="mkdir -p '$dir' && printf '' > '$path'"
    else
      command="printf '' > '$path'"
    fi
    if adb shell run-as "$package" sh -c "$command" >/dev/null 2>&1; then
      return 0
    fi
  done

  return 1
}

system_crash_attempts=0
while true; do
  cleanup_flags
  launch_screenshot_harness
  if wait_for_harness; then
    break
  fi

  wait "$harness_pid" >/dev/null 2>&1 || true
  harness_pid=""

  if [ "$HARNESS_EXIT_REASON" = "system_crash" ] && [ $system_crash_attempts -lt $MAX_SYSTEM_CRASH_RETRIES ]; then
    system_crash_attempts=$((system_crash_attempts + 1))
    echo "Detected Android system crash during screenshot harness; waiting for recovery before retrying" >&2
    wait_for_activity_manager
    continue
  fi

  exit 1
done

adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 0 || true
adb shell cmd uimode night no || true
adb shell settings put secure high_text_contrast_enabled 0 || true
adb shell settings put secure accessibility_display_daltonizer_enabled 0 || true
sleep 5

capture_set() {
  local label="$1"
  local orientation="$2"
  local night_mode="$3"
  local high_contrast="$4"
  local daltonizer="$5"
  local count="$6"

  local target_dir="${base_dir}/${label}"
  mkdir -p "$target_dir"

  if [ "$orientation" = "portrait" ]; then
    adb shell settings put system user_rotation 0 || true
  else
    adb shell settings put system user_rotation 1 || true
  fi

  adb shell cmd uimode night "$night_mode" || true
  adb shell settings put secure high_text_contrast_enabled "$high_contrast" || true

  if [ "$daltonizer" = "none" ]; then
    adb shell settings put secure accessibility_display_daltonizer_enabled 0 || true
  else
    adb shell settings put secure accessibility_display_daltonizer_enabled 1 || true
    adb shell settings put secure accessibility_display_daltonizer "$daltonizer" || true
  fi

  # Allow the UI to settle after changing orientation/theme/accessibility flags
  sleep 4

  for i in $(seq -w 1 "$count"); do
    adb exec-out screencap -p > "$target_dir/screenshot_${i}.png"
    sleep 1
  done
}

capture_set "light-portrait-standard" portrait no 0 none 5
capture_set "light-portrait-accessibility" portrait no 1 0 4
capture_set "dark-portrait-standard" portrait yes 0 none 4
capture_set "dark-landscape-accessibility" landscape yes 1 1 4
capture_set "light-landscape-standard" landscape no 0 none 3

if [ -z "$HARNESS_FLAG_PACKAGE" ]; then
  HARNESS_FLAG_PACKAGE="$HARNESS_RUN_AS_PACKAGE"
fi

if ! signal_screenshot_completion "$HARNESS_FLAG_PACKAGE"; then
  echo "::error::Failed to signal screenshot harness completion" >&2
  exit 1
fi

if ! wait "$harness_pid"; then
  status=$?
  echo "::error::Screenshot harness instrumentation reported failure" >&2
  cat "$HARNESS_LOG" >&2 || true
  collect_harness_logcat
  collect_app_crash_logs
  exit $status
fi

trap - EXIT
finish_harness

adb shell settings put system user_rotation 0 || true
adb shell cmd uimode night no || true
adb shell settings put secure high_text_contrast_enabled 0 || true
adb shell settings put secure accessibility_display_daltonizer_enabled 0 || true
