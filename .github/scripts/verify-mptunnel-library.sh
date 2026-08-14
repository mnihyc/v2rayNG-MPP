#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <arm64-v8a|x86_64> <libmptunnel.so>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
abi="$1"
library="$2"
[[ -f "$library" && ! -L "$library" ]] || {
  echo "missing regular MPTUNNEL library: $library" >&2
  exit 1
}

case "$abi" in
  arm64-v8a) expected_machine="AArch64" ;;
  x86_64) expected_machine="Advanced Micro Devices X86-64" ;;
  *) usage ;;
esac

machine="$(readelf --file-header "$library" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
[[ "$machine" == "$expected_machine" ]] || {
  echo "$library has machine '$machine', expected '$expected_machine'" >&2
  exit 1
}

mapfile -t load_alignments < <(
  readelf --wide --program-headers "$library" |
    awk '$1 == "LOAD" { print $NF }'
)
[[ "${#load_alignments[@]}" -gt 0 ]] || {
  echo "$library has no ELF LOAD segments" >&2
  exit 1
}
for alignment in "${load_alignments[@]}"; do
  [[ "$alignment" =~ ^0x[0-9a-fA-F]+$ ]] || {
    echo "$library has an invalid LOAD alignment: $alignment" >&2
    exit 1
  }
  (( alignment >= 0x4000 )) || {
    echo "$library has a LOAD segment aligned below 16 KiB: $alignment" >&2
    exit 1
  }
done

expected_symbols="$(mktemp)"
actual_symbols="$(mktemp)"
trap 'rm -f "$expected_symbols" "$actual_symbols"' EXIT
printf '%s\n' \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeFinalizeEditor \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeIsRunning \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeMigrateEditor \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativePatchEditor \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeProjectEditor \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeStart \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeState \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeStatsJson \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeStop \
  Java_com_v2ray_ang_mpp_MptunnelNative_nativeVersion \
  > "$expected_symbols"

readelf --wide --dyn-syms "$library" |
  awk '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" && \
       $8 ~ /^Java_com_v2ray_ang_mpp_MptunnelNative_/ { print $8 }' |
  LC_ALL=C sort -u > "$actual_symbols"
diff -u "$expected_symbols" "$actual_symbols"

echo "verified MPTUNNEL JNI: $abi $library"
