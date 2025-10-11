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

log() {
  local message="$1"
  printf '[native-crash] %s\n' "$message" >&2
}

pdfium_only="${PDFIUM_ONLY:-true}"
backtrace_path="${output_dir}/pdfium-native-backtraces.txt"

tombstone_dir="${output_dir}/tombstones"
mkdir -p "$tombstone_dir"

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

log "Wrote Pdfium native backtrace summary to $backtrace_path"
exit 0
