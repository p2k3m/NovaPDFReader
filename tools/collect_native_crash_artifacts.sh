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

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <output-dir>" >&2
  exit 64
fi

output_dir="$1"
shift || true
output_dir="${output_dir%/}"
mkdir -p "$output_dir"

serial="${ANDROID_SERIAL:-}"
adb_cmd=(adb)
if [ -n "$serial" ]; then
  adb_cmd+=(-s "$serial")
fi

package_name="${PACKAGE_NAME:-com.novapdf.reader}"
test_package_name="${TEST_PACKAGE_NAME:-${package_name}.test}"
collect_native_libs="${COLLECT_NATIVE_LIBS:-true}"
native_lib_root="${output_dir}/native-libs"
anr_dir="${output_dir}/anr"

log() {
  local message="$1"
  printf '[native-crash] %s\n' "$message" >&2
}

determine_active_abis() {
  local package="$1"
  local -n result_ref="$2"
  result_ref=()

  local dumpsys_output
  if ! dumpsys_output=$(timeout 15s "${adb_cmd[@]}" shell dumpsys package "$package" 2>/dev/null); then
    return 1
  fi

  local primary secondary
  primary=$(printf '%s\n' "$dumpsys_output" | awk -F'=' '/primaryCpuAbi=/{print $2; exit}' | tr -d '\r')
  secondary=$(printf '%s\n' "$dumpsys_output" | awk -F'=' '/secondaryCpuAbi=/{print $2; exit}' | tr -d '\r')

  if [ -n "$primary" ] && [ "$primary" != "(null)" ]; then
    result_ref+=("$primary")
  fi

  if [ -n "$secondary" ] && [ "$secondary" != "(null)" ] && [ "$secondary" != "$primary" ]; then
    result_ref+=("$secondary")
  fi

  if [ "${#result_ref[@]}" -eq 0 ]; then
    local fallback
    fallback=$(timeout 5s "${adb_cmd[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)
    if [ -n "$fallback" ]; then
      result_ref+=("$fallback")
    fi
  fi

  return 0
}

extract_native_libs_for_package() {
  local package="$1"
  local destination_root="$2"

  if ! command -v unzip >/dev/null 2>&1; then
    log "Skipping native library extraction for $package because unzip is unavailable"
    return
  fi

  local -a active_abis
  if ! determine_active_abis "$package" active_abis || [ "${#active_abis[@]}" -eq 0 ]; then
    log "Unable to determine active ABI for $package; skipping native library extraction"
    return
  fi

  local apk_paths
  if ! apk_paths=$(timeout 30s "${adb_cmd[@]}" shell pm path "$package" 2>/dev/null); then
    log "Failed to query APK paths for $package; skipping native library extraction"
    return
  fi
  apk_paths=$(printf '%s\n' "$apk_paths" | tr -d '\r' | awk 'NF')
  if [ -z "$apk_paths" ]; then
    log "No APK paths reported for $package; skipping native library extraction"
    return
  fi

  local package_root="${destination_root%/}/$package"
  local apk_output_dir="${package_root}/apks"
  local libs_output_dir="${package_root}/native-libs"
  mkdir -p "$apk_output_dir" "$libs_output_dir"

  log "Extracting native libraries for $package (ABIs: ${active_abis[*]})"
  local saved_any=false

  while IFS= read -r raw_path; do
    [ -n "$raw_path" ] || continue
    local remote_path="${raw_path#*:}"
    if [ -z "$remote_path" ]; then
      continue
    fi

    local apk_name
    apk_name=$(basename "$remote_path")
    local local_apk_path="${apk_output_dir}/${apk_name}"
    if ! timeout 60s "${adb_cmd[@]}" pull "$remote_path" "$local_apk_path" >/dev/null 2>&1; then
      log "Failed to pull $remote_path for $package"
      continue
    fi

    local temp_dir
    temp_dir=$(mktemp -d)
    local -a patterns=()
    local abi
    for abi in "${active_abis[@]}"; do
      patterns+=("lib/${abi}/*")
    done

    if unzip -qn "$local_apk_path" "${patterns[@]}" -d "$temp_dir" >/dev/null 2>&1; then
      if [ -d "$temp_dir/lib" ]; then
        while IFS= read -r lib_path; do
          local relative="${lib_path#${temp_dir}/lib/}"
          local current_abi="${relative%%/*}"
          local remainder="${relative#*/}"
          if [ -z "$remainder" ] || [ "$current_abi" = "$relative" ]; then
            continue
          fi
          local destination_path="${libs_output_dir}/${current_abi}/${remainder}"
          mkdir -p "$(dirname "$destination_path")"
          if [ -f "$destination_path" ]; then
            continue
          fi
          cp "$lib_path" "$destination_path"
          saved_any=true
        done < <(find "$temp_dir/lib" -type f -name '*.so' -print)
      fi
    else
      log "No native libraries for ${active_abis[*]} found in ${apk_name}"
    fi

    rm -rf "$temp_dir"
  done <<< "$apk_paths"

  if [ "$saved_any" = true ]; then
    log "Saved native libraries for $package to ${libs_output_dir}"
  else
    log "No native libraries extracted for $package"
  fi
}

pdfium_only="${PDFIUM_ONLY:-true}"
backtrace_path="${output_dir}/pdfium-native-backtraces.txt"

tombstone_dir="${output_dir}/tombstones"
mkdir -p "$tombstone_dir"
mkdir -p "$anr_dir"

adb_root_enabled=false
if timeout 30s "${adb_cmd[@]}" root >/dev/null 2>&1; then
  adb_root_enabled=true
  log "ADB root enabled for diagnostics"
else
  log "Failed to enable adb root; continuing without elevated access"
fi

cleanup_root() {
  if [ "$adb_root_enabled" = true ]; then
    "${adb_cmd[@]}" unroot >/dev/null 2>&1 || true
  fi
}
trap cleanup_root EXIT

collect_anr_artifacts() {
  local status_path="$anr_dir/status.txt"
  rm -f "$status_path"

  if ! timeout 15s "${adb_cmd[@]}" shell ls /data/anr >/dev/null 2>&1; then
    log "Unable to access /data/anr on device"
    printf 'Unable to access /data/anr on device (requires root privileges).\n' > "$status_path"
    return
  fi

  mapfile -t anr_entries < <(timeout 15s "${adb_cmd[@]}" shell ls -1 /data/anr 2>/dev/null | tr -d '\r' | awk 'NF')

  if [ "${#anr_entries[@]}" -eq 0 ]; then
    log "No artifacts present under /data/anr"
    printf 'No ANR traces were present under /data/anr.\n' > "$status_path"
    return
  fi

  local saved_any=false
  local entry
  for entry in "${anr_entries[@]}"; do
    local remote="/data/anr/${entry}"
    if timeout 120s "${adb_cmd[@]}" pull "$remote" "$anr_dir/" >/dev/null 2>&1; then
      log "Saved ANR artifact $remote"
      saved_any=true
    else
      log "Failed to export ANR artifact $remote"
    fi
  done

  if [ "$saved_any" = false ]; then
    printf 'Failed to export ANR traces from /data/anr.\n' > "$status_path"
  fi
}

collect_anr_artifacts

if ! timeout 15s "${adb_cmd[@]}" shell ls /data/tombstones >/dev/null 2>&1; then
  log "Unable to access /data/tombstones on device"
  printf 'Unable to access /data/tombstones on device (requires root privileges).\n' > "$backtrace_path"
  exit 0
fi

mapfile -t tombstones < <(timeout 15s "${adb_cmd[@]}" shell ls -1 /data/tombstones 2>/dev/null | tr -d '\r' | awk 'NF')

if [ "${#tombstones[@]}" -eq 0 ]; then
  log "No tombstones present under /data/tombstones"
  printf 'No tombstones were present under /data/tombstones.\n' > "$backtrace_path"
  exit 0
fi

declare -a saved_paths=()
for tombstone in "${tombstones[@]}"; do
  remote="/data/tombstones/${tombstone}"
  dest="${tombstone_dir}/${tombstone}"
  if timeout 60s "${adb_cmd[@]}" shell cat "$remote" > "$dest"; then
    log "Saved tombstone $remote to $dest"
    saved_paths+=("$dest")
  else
    log "Failed to export tombstone $remote"
    rm -f "$dest"
  fi
done

if [ "${#saved_paths[@]}" -eq 0 ]; then
  log "Unable to export tombstones from device"
  printf 'Unable to export tombstones from device.\n' > "$backtrace_path"
  exit 0
fi

python3 - "$backtrace_path" "$pdfium_only" "${saved_paths[@]}" <<'PY'
import sys
import pathlib
from typing import Iterable, List

def extract_backtrace_blocks(lines: List[str]) -> List[str]:
    blocks: List[str] = []
    current: List[str] = []
    capturing = False

    for line in lines:
        stripped = line.strip()
        if stripped.endswith("backtrace:"):
            if current:
                blocks.append("\n".join(current))
                current = []
            capturing = True
            current.append(line)
            continue

        if capturing:
            if not stripped:
                current.append(line)
                continue
            if line.startswith(" ") or line.startswith("\t") or line.startswith("#"):
                current.append(line)
                continue

            blocks.append("\n".join(current))
            current = []
            capturing = False

    if capturing and current:
        blocks.append("\n".join(current))

    return blocks


def contains_pdfium(lines: Iterable[str]) -> bool:
    for line in lines:
        if "pdfium" in line.lower():
            return True
    return False


def write_summary(output_path: pathlib.Path, pdfium_only: bool, tombstone_paths: Iterable[pathlib.Path]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as handle:
        wrote_any = False

        for path in tombstone_paths:
            try:
                content = path.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                handle.write(f"Failed to read {path.name}: {exc}\n")
                continue

            lines = content.splitlines()
            if pdfium_only and not contains_pdfium(lines):
                continue

            blocks = extract_backtrace_blocks(lines)
            if not blocks:
                continue

            wrote_any = True
            handle.write(f"===== {path.name} =====\n")
            for block in blocks:
                handle.write(block)
                if not block.endswith("\n"):
                    handle.write("\n")
                handle.write("\n")

        if not wrote_any:
            if pdfium_only:
                handle.write("No Pdfium references detected within collected tombstones.\n")
            else:
                handle.write("No backtrace sections detected within collected tombstones.\n")


def main() -> None:
    if len(sys.argv) < 4:
        sys.exit("Usage: extract_backtraces.py <output> <pdfium_only> <tombstones>...")

    output_path = pathlib.Path(sys.argv[1])
    pdfium_only = sys.argv[2].lower() not in {"0", "false", "no"}
    tombstone_paths = [pathlib.Path(arg) for arg in sys.argv[3:]]

    write_summary(output_path, pdfium_only, tombstone_paths)


if __name__ == "__main__":
    main()
PY

case "${collect_native_libs,,}" in
  ""|"0"|"false"|"no")
    ;;
  *)
    extract_native_libs_for_package "$package_name" "$native_lib_root"
    if [ -n "$test_package_name" ] && [ "$test_package_name" != "$package_name" ]; then
      extract_native_libs_for_package "$test_package_name" "$native_lib_root"
    fi
    ;;
esac

log "Wrote Pdfium native backtrace summary to $backtrace_path"
exit 0
