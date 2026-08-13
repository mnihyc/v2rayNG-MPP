#!/usr/bin/env bash
set -euo pipefail

readonly release_repository="mnihyc/mptunnel"
readonly version_name="version.json"
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly script_root
repository_root="$(cd "$script_root/../.." && pwd)"
readonly repository_root
readonly output_root="${1:-$repository_root/V2rayNG/app/libs}"

[[ -n "${GITHUB_TOKEN:-}" ]] || {
  echo "GITHUB_TOKEN is required to verify immutable release metadata" >&2
  exit 2
}
for command in curl file jq sha256sum tar readelf; do
  command -v "$command" >/dev/null || {
    echo "required command is unavailable: $command" >&2
    exit 2
  }
done

work_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/mptunnel-android.XXXXXX")"
trap 'rm -rf "$work_root"' EXIT
release_json="$work_root/release.json"

api_get() {
  curl --fail --silent --show-error --location --retry 3 \
    --header "Authorization: Bearer ${GITHUB_TOKEN}" \
    --header "Accept: application/vnd.github+json" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "$1"
}

api_get "https://api.github.com/repos/${release_repository}/releases/latest" \
  > "$release_json"
release_id="$(jq -er .id "$release_json")"
readonly release_id
[[ "$release_id" =~ ^[1-9][0-9]*$ ]] || {
  echo "latest MPTUNNEL release has an invalid release ID" >&2
  exit 1
}
release_tag="$(jq -er .tag_name "$release_json")"
readonly release_tag
readonly stable_semver_pattern='^v(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$'
[[ "$release_tag" =~ $stable_semver_pattern ]] || {
  echo "latest MPTUNNEL release tag is not stable vSemVer: $release_tag" >&2
  exit 1
}
release_version="${release_tag#v}"
readonly release_version

jq -e --arg tag "$release_tag" '
  (.id | type) == "number" and
  .tag_name == $tag and
  .draft == false and
  .prerelease == false and
  .immutable == true and
  (.assets | type) == "array"
' "$release_json" >/dev/null

tag_ref="$(api_get "https://api.github.com/repos/${release_repository}/git/ref/tags/${release_tag}")"
jq -e --arg ref "refs/tags/$release_tag" '.ref == $ref' <<<"$tag_ref" >/dev/null
object_type="$(jq -er .object.type <<<"$tag_ref")"
object_sha="$(jq -er .object.sha <<<"$tag_ref")"
[[ "$object_type" == tag ]] || {
  echo "latest MPTUNNEL release must use an annotated tag" >&2
  exit 1
}
for depth in 1 2 3 4; do
  case "$object_type" in
    commit) break ;;
    tag)
      tag_object="$(api_get "https://api.github.com/repos/${release_repository}/git/tags/${object_sha}")"
      if [[ "$depth" == 1 ]]; then
        jq -e --arg tag "$release_tag" '.tag == $tag' <<<"$tag_object" >/dev/null
      fi
      object_type="$(jq -er .object.type <<<"$tag_object")"
      object_sha="$(jq -er .object.sha <<<"$tag_object")"
      ;;
    *)
      echo "release tag resolves to unsupported Git object type: $object_type" >&2
      exit 1
      ;;
  esac
done
[[ "$object_type" == commit && "$object_sha" =~ ^[0-9a-f]{40}$ ]] || {
  echo "could not resolve $release_tag to one source commit" >&2
  exit 1
}

download_asset() {
  local name="$1"
  local destination="$2"
  local matches id digest actual
  matches="$(jq --arg name "$name" '[.assets[] | select(.name == $name)]' "$release_json")"
  [[ "$(jq -r length <<<"$matches")" == 1 ]] || {
    echo "release must contain exactly one asset named $name" >&2
    exit 1
  }
  id="$(jq -er '.[0].id' <<<"$matches")"
  digest="$(jq -er '.[0].digest' <<<"$matches")"
  [[ "$id" =~ ^[1-9][0-9]*$ && "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || {
    echo "$name has invalid immutable GitHub asset metadata" >&2
    exit 1
  }
  curl --fail --silent --show-error --location --retry 3 \
    --header "Authorization: Bearer ${GITHUB_TOKEN}" \
    --header "Accept: application/octet-stream" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/${release_repository}/releases/assets/${id}" \
    --output "$destination"
  actual="$(sha256sum "$destination" | awk '{print $1}')"
  [[ "sha256:$actual" == "$digest" ]] || {
    echo "$name does not match its immutable GitHub asset digest" >&2
    exit 1
  }
  printf '%s\t%s\n' "$id" "$actual"
}

version_file="$work_root/$version_name"
download_asset "$version_name" "$version_file" >/dev/null

jq -e \
  --arg version "$release_version" \
  --arg tag "$release_tag" \
  --arg commit "$object_sha" \
  --arg repository "$release_repository" '
  (keys | sort) == (["assets", "commit", "product", "schema_version", "tag", "version"] | sort) and
  .schema_version == 2 and
  .product == "mptunnel" and
  .version == $version and
  .tag == $tag and
  .commit == $commit and
  (.assets | type) == "array" and
  (.assets | length) == 8 and
  ([.assets[].name] | unique | length) == 8 and
  all(.assets[];
    (keys | sort) == ["download_url", "name"] and
    (.name | type) == "string" and
    (.name | test("^[A-Za-z0-9][A-Za-z0-9._+-]*$")) and
    .name != "version.json" and
    .download_url ==
      ("https://github.com/" + $repository + "/releases/download/" + $tag + "/" + .name))
' "$version_file" >/dev/null

expected_release_assets="$work_root/expected-release-assets.txt"
actual_release_assets="$work_root/actual-release-assets.txt"
{
  jq -r '.assets[].name' "$version_file"
  printf '%s\n' "$version_name"
} | LC_ALL=C sort > "$expected_release_assets"
jq -r '.assets[].name' "$release_json" | LC_ALL=C sort > "$actual_release_assets"
diff -u "$expected_release_assets" "$actual_release_assets"

mapfile -t existing_libraries < <(
  find "$output_root" -mindepth 2 -maxdepth 2 -type f -name libmptunnel.so -print 2>/dev/null || true
)
for library in "${existing_libraries[@]}"; do
  case "$library" in
    "$output_root/arm64-v8a/libmptunnel.so"|"$output_root/x86_64/libmptunnel.so") ;;
    *)
      echo "unexpected pre-existing MPTUNNEL ABI library: $library" >&2
      exit 1
      ;;
  esac
done

declare -A asset_names asset_ids asset_sha256
asset_names[arm64-v8a]="mptunnel-${release_version}-android-arm64.tar.gz"
asset_names[x86_64]="mptunnel-${release_version}-android-x86_64.tar.gz"

for abi in arm64-v8a x86_64; do
  archive_name="${asset_names[$abi]}"
  archive="$work_root/$archive_name"
  IFS=$'\t' read -r asset_ids[$abi] asset_sha256[$abi] < <(
    download_asset "$archive_name" "$archive"
  )
  package_root="${archive_name%.tar.gz}"
  case "$abi" in
    arm64-v8a) cli_arch='aarch64|arm64' ;;
    x86_64) cli_arch='x86[-_ ]64|x86-64' ;;
  esac
  expected_inventory="$work_root/$abi-expected-inventory.txt"
  actual_inventory="$work_root/$abi-actual-inventory.txt"
  printf '%s\n' \
    "$package_root/" \
    "$package_root/README.md" \
    "$package_root/examples/" \
    "$package_root/examples/client.toml" \
    "$package_root/examples/server.toml" \
    "$package_root/$abi/" \
    "$package_root/$abi/libmptunnel.so" \
    "$package_root/mptunnel" \
    | LC_ALL=C sort > "$expected_inventory"
  tar --list --gzip --file "$archive" | LC_ALL=C sort > "$actual_inventory"
  diff -u "$expected_inventory" "$actual_inventory"

  extract_root="$work_root/extracted-$abi"
  mkdir -p "$extract_root"
  tar --extract --gzip --file "$archive" --directory "$extract_root" \
    --no-same-owner --no-same-permissions
  if find "$extract_root" -type l -print -quit | grep -q .; then
    echo "$archive_name contains a symbolic link" >&2
    exit 1
  fi
  for relative in README.md examples/client.toml examples/server.toml mptunnel "$abi/libmptunnel.so"; do
    [[ -f "$extract_root/$package_root/$relative" && ! -L "$extract_root/$package_root/$relative" ]] || {
      echo "$archive_name member is not a regular file: $relative" >&2
      exit 1
    }
  done
  file "$extract_root/$package_root/mptunnel" | grep -Eiq "$cli_arch" || {
    echo "$archive_name CLI architecture does not match $abi" >&2
    exit 1
  }
  library="$extract_root/$package_root/$abi/libmptunnel.so"
  "$script_root/verify-mptunnel-library.sh" "$abi" "$library"
  mkdir -p "$output_root/$abi"
  install -m 0644 "$library" "$output_root/$abi/libmptunnel.so"
done

[[ "${asset_ids[arm64-v8a]}" != "${asset_ids[x86_64]}" ]] || {
  echo "Android MPTUNNEL archives unexpectedly share one asset ID" >&2
  exit 1
}

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version=$release_version"
    echo "tag=$release_tag"
    echo "commit=$object_sha"
    echo "release_id=$release_id"
    echo "arm64_asset=${asset_names[arm64-v8a]}"
    echo "arm64_asset_id=${asset_ids[arm64-v8a]}"
    echo "arm64_sha256=${asset_sha256[arm64-v8a]}"
    echo "x86_64_asset=${asset_names[x86_64]}"
    echo "x86_64_asset_id=${asset_ids[x86_64]}"
    echo "x86_64_sha256=${asset_sha256[x86_64]}"
  } >> "$GITHUB_OUTPUT"
fi
printf 'resolved MPTUNNEL version=%s tag=%s commit=%s release_id=%s arm64_asset_id=%s x86_64_asset_id=%s\n' \
  "$release_version" "$release_tag" "$object_sha" "$release_id" \
  "${asset_ids[arm64-v8a]}" "${asset_ids[x86_64]}"
