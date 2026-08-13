#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <apk-output-directory> <release-directory>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
apk_root="$1"
release_root="$2"
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly script_root
repository_root="$(cd "$script_root/../.." && pwd)"
readonly repository_root
readonly metadata="$apk_root/output-metadata.json"

for variable in GITHUB_REPOSITORY GITHUB_REF_NAME GITHUB_SHA \
  MPTUNNEL_VERSION MPTUNNEL_TAG MPTUNNEL_COMMIT MPTUNNEL_RELEASE_ID \
  MPTUNNEL_ASSET MPTUNNEL_ASSET_ID MPTUNNEL_SHA256 \
  ANDROID_SDK_ROOT; do
  [[ -n "${!variable:-}" ]] || {
    echo "required environment variable is empty: $variable" >&2
    exit 2
  }
done
for command in jq sha256sum unzip zipinfo readelf; do
  command -v "$command" >/dev/null || {
    echo "required command is unavailable: $command" >&2
    exit 2
  }
done
[[ -f "$metadata" ]] || {
  echo "missing APK output metadata: $metadata" >&2
  exit 1
}
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]] || {
  echo "GITHUB_SHA is not a full commit ID" >&2
  exit 1
}
[[ "$MPTUNNEL_COMMIT" =~ ^[0-9a-f]{40}$ ]] || {
  echo "MPTUNNEL_COMMIT is not a full commit ID" >&2
  exit 1
}
[[ "$MPTUNNEL_VERSION" =~ ^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$ ]] || {
  echo "MPTUNNEL_VERSION is not stable SemVer" >&2
  exit 1
}
[[ "$MPTUNNEL_TAG" == "v$MPTUNNEL_VERSION" ]] || {
  echo "MPTUNNEL tag/version mismatch" >&2
  exit 1
}
[[ "$MPTUNNEL_ASSET" == "mptunnel-${MPTUNNEL_VERSION}-android-jni.tar.gz" ]] || {
  echo "MPTUNNEL asset/version mismatch" >&2
  exit 1
}
[[ "$MPTUNNEL_RELEASE_ID" =~ ^[1-9][0-9]*$ && "$MPTUNNEL_ASSET_ID" =~ ^[1-9][0-9]*$ ]] || {
  echo "MPTUNNEL release or asset ID is invalid" >&2
  exit 1
}
[[ "$MPTUNNEL_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
  echo "MPTUNNEL_SHA256 is not a SHA-256 digest" >&2
  exit 1
}

jq -e '
  .artifactType.type == "APK" and
  .variantName == "fdroidRelease" and
  (.elements | length) == 3 and
  ([.elements[] | select(.type == "UNIVERSAL" and (.filters | length) == 0)] | length) == 1 and
  ([.elements[] | select(
    .type == "ONE_OF_MANY" and
    .filters == [{"filterType":"ABI","value":"arm64-v8a"}]
  )] | length) == 1 and
  ([.elements[] | select(
    .type == "ONE_OF_MANY" and
    .filters == [{"filterType":"ABI","value":"x86_64"}]
  )] | length) == 1 and
  ([.elements[].versionName] | unique | length) == 1 and
  all(.elements[]; (.outputFile | type) == "string" and
    (.outputFile | test("^[A-Za-z0-9._+-]+\\.apk$")))
' "$metadata" >/dev/null

version_name="$(jq -er '.elements[0].versionName' "$metadata")"
application_id="$(jq -er .applicationId "$metadata")"
[[ "$application_id" == "com.v2ray.ang.mpp.fdroid" ]] || {
  echo "release APK applicationId is '$application_id', expected 'com.v2ray.ang.mpp.fdroid'" >&2
  exit 1
}
[[ "$GITHUB_REF_NAME" == "v$version_name" ]] || {
  echo "release tag $GITHUB_REF_NAME does not match APK version v$version_name" >&2
  exit 1
}
[[ "$GITHUB_REF_NAME" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
  echo "release tag is not a version tag: $GITHUB_REF_NAME" >&2
  exit 1
}

mapfile -t metadata_apks < <(jq -r '.elements[].outputFile' "$metadata" | LC_ALL=C sort)
mapfile -t disk_apks < <(
  find "$apk_root" -maxdepth 1 -type f -name '*.apk' -printf '%f\n' | LC_ALL=C sort
)
[[ "${#metadata_apks[@]}" == 3 && "${#disk_apks[@]}" == 3 ]] || {
  echo "expected exactly three release APKs" >&2
  exit 1
}
diff -u <(printf '%s\n' "${metadata_apks[@]}") <(printf '%s\n' "${disk_apks[@]}")

build_tools="$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -n 1)"
[[ -n "$build_tools" ]] || {
  echo "Android build tools are unavailable" >&2
  exit 1
}
apksigner="$ANDROID_SDK_ROOT/build-tools/$build_tools/apksigner"
zipalign="$ANDROID_SDK_ROOT/build-tools/$build_tools/zipalign"
[[ -x "$apksigner" && -x "$zipalign" ]] || {
  echo "apksigner or zipalign is unavailable in build-tools/$build_tools" >&2
  exit 1
}

[[ ! -e "$release_root" ]] || {
  echo "release staging path already exists: $release_root" >&2
  exit 1
}
mkdir -p "$release_root"
work_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/verify-apks.XXXXXX")"
trap 'rm -rf "$work_root"' EXIT
cert_digest=""
apk_records="$work_root/apks.jsonl"

for kind in arm64-v8a x86_64 universal; do
  if [[ "$kind" == universal ]]; then
    output_file="$(jq -er '.elements[] | select(.type == "UNIVERSAL") | .outputFile' "$metadata")"
    expected_abis=(arm64-v8a x86_64)
  else
    output_file="$(jq -er --arg abi "$kind" '
      .elements[] | select(any(.filters[]?; .filterType == "ABI" and .value == $abi)) |
      .outputFile
    ' "$metadata")"
    expected_abis=("$kind")
  fi
  apk="$apk_root/$output_file"
  [[ -f "$apk" ]] || {
    echo "metadata names a missing APK: $output_file" >&2
    exit 1
  }

  "$zipalign" -c -P 16 4 "$apk"
  signature_report="$work_root/$kind-signature.txt"
  "$apksigner" verify --verbose --print-certs "$apk" > "$signature_report"
  current_cert="$(
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$signature_report" |
      LC_ALL=C sort -u
  )"
  [[ "$current_cert" =~ ^[0-9a-f]{64}$ ]] || {
    echo "$output_file has no single verifiable signing certificate" >&2
    exit 1
  }
  if [[ -z "$cert_digest" ]]; then
    cert_digest="$current_cert"
  else
    [[ "$current_cert" == "$cert_digest" ]] || {
      echo "release APKs were not signed by the same certificate" >&2
      exit 1
    }
  fi

  mapfile -t packaged_abis < <(
    zipinfo -1 "$apk" |
      sed -n 's#^lib/\([^/]*\)/[^/]*$#\1#p' |
      LC_ALL=C sort -u
  )
  diff -u \
    <(printf '%s\n' "${expected_abis[@]}" | LC_ALL=C sort) \
    <(printf '%s\n' "${packaged_abis[@]}")

  for abi in "${expected_abis[@]}"; do
    mapfile -t mptunnel_entries < <(
      zipinfo -1 "$apk" | grep -F "lib/$abi/libmptunnel.so" || true
    )
    [[ "${#mptunnel_entries[@]}" == 1 &&
      "${mptunnel_entries[0]}" == "lib/$abi/libmptunnel.so" ]] || {
      echo "$output_file does not contain exactly one $abi/libmptunnel.so" >&2
      exit 1
    }
    embedded="$work_root/$kind-$abi-libmptunnel.so"
    unzip -p "$apk" "lib/$abi/libmptunnel.so" > "$embedded"
    "$script_root/verify-mptunnel-library.sh" "$abi" "$embedded"
    cmp --silent "$repository_root/V2rayNG/app/libs/$abi/libmptunnel.so" "$embedded" || {
      echo "$output_file changed the staged $abi MPTUNNEL library" >&2
      exit 1
    }
  done

  install -m 0644 "$apk" "$release_root/$output_file"
  apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"
  jq -cn \
    --arg name "$output_file" \
    --arg abi "$kind" \
    --arg sha256 "$apk_sha256" \
    '{name:$name, abi:$abi, sha256:$sha256}' >> "$apk_records"
done

(
  cd "$release_root"
  sha256sum ./*.apk | sed 's#  \./#  #' | LC_ALL=C sort -k2 > SHA256SUMS
)
jq -s \
  --arg repository "$GITHUB_REPOSITORY" \
  --arg tag "$GITHUB_REF_NAME" \
  --arg commit "$GITHUB_SHA" \
  --arg version "$version_name" \
  --arg application_id "$application_id" \
  --arg signing_cert "$cert_digest" \
  --arg mptunnel_version "$MPTUNNEL_VERSION" \
  --arg mptunnel_tag "$MPTUNNEL_TAG" \
  --arg mptunnel_commit "$MPTUNNEL_COMMIT" \
  --arg mptunnel_release_id "$MPTUNNEL_RELEASE_ID" \
  --arg mptunnel_asset "$MPTUNNEL_ASSET" \
  --arg mptunnel_asset_id "$MPTUNNEL_ASSET_ID" \
  --arg mptunnel_sha256 "$MPTUNNEL_SHA256" '
  {
    schema_version: 1,
    repository: $repository,
    tag: $tag,
    commit: $commit,
    version_name: $version,
    application_id: $application_id,
    signing_certificate_sha256: $signing_cert,
    mptunnel: {
      version: $mptunnel_version,
      tag: $mptunnel_tag,
      commit: $mptunnel_commit,
      release_id: ($mptunnel_release_id | tonumber),
      asset: $mptunnel_asset,
      asset_id: ($mptunnel_asset_id | tonumber),
      sha256: $mptunnel_sha256
    },
    apks: .
  }
' "$apk_records" > "$release_root/build-provenance.json"

mapfile -t release_files < <(
  find "$release_root" -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort
)
[[ "${#release_files[@]}" == 5 ]] || {
  echo "release staging must contain three APKs, SHA256SUMS and build-provenance.json" >&2
  exit 1
}
[[ "$(printf '%s\n' "${release_files[@]}" | grep -c '\.apk$')" == 3 ]]
[[ -f "$release_root/SHA256SUMS" && -f "$release_root/build-provenance.json" ]]

echo "verified and staged three signed release APKs for $GITHUB_REF_NAME ($GITHUB_SHA)"
