#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <package_name> <logs_dir> <stage>" >&2
  exit 1
fi

package_name="$1"
logs_dir="$2"
stage="$3"

mkdir -p "$logs_dir"

if ! command -v adb >/dev/null 2>&1; then
  echo "::warning::adb not available for heap dump capture" >&2
  exit 0
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "::warning::Device unavailable for heap dump capture" >&2
  exit 0
fi

sanitized_stage="${stage//[^a-zA-Z0-9_-]/_}"
sanitized_package="${package_name//./_}"
host_path="${logs_dir}/${sanitized_stage}-heapdump.hprof"
device_path="/sdcard/${sanitized_package}-${sanitized_stage}-heapdump.hprof"

if adb shell am dumpheap "$package_name" "$device_path"; then
  if adb pull "$device_path" "$host_path"; then
    echo "Captured heap dump at $host_path" >&2
  else
    echo "::warning::Failed to pull heap dump from $device_path" >&2
  fi
else
  echo "::warning::Failed to invoke am dumpheap for $package_name" >&2
fi

adb shell rm "$device_path" >/dev/null 2>&1 || true

