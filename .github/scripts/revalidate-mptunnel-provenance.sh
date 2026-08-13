#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <build-provenance.json>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
readonly provenance="$1"
readonly mptunnel_repository="${MPTUNNEL_REPOSITORY:-mnihyc/mptunnel}"

[[ -f "$provenance" ]] || {
  echo "missing build provenance: $provenance" >&2
  exit 1
}
[[ -n "${GH_TOKEN:-}" ]] || {
  echo "GH_TOKEN is required" >&2
  exit 2
}
for command in gh jq; do
  command -v "$command" >/dev/null || {
    echo "required command is unavailable: $command" >&2
    exit 2
  }
done

mptunnel_release_id="$(jq -er .mptunnel.release_id "$provenance")"
mptunnel_asset_id="$(jq -er .mptunnel.asset_id "$provenance")"
mptunnel_tag="$(jq -er .mptunnel.tag "$provenance")"
mptunnel_commit="$(jq -er .mptunnel.commit "$provenance")"
mptunnel_asset_name="$(jq -er .mptunnel.asset "$provenance")"
mptunnel_digest="sha256:$(jq -er .mptunnel.sha256 "$provenance")"

[[ "$mptunnel_release_id" =~ ^[1-9][0-9]*$ && "$mptunnel_asset_id" =~ ^[1-9][0-9]*$ ]] || {
  echo "invalid MPTUNNEL release or asset ID in provenance" >&2
  exit 1
}

mptunnel_release="$(
  gh api "repos/${mptunnel_repository}/releases/${mptunnel_release_id}"
)"
jq -e \
  --argjson id "$mptunnel_release_id" \
  --arg tag "$mptunnel_tag" '
    .id == $id and .tag_name == $tag and
    .draft == false and .prerelease == false and .immutable == true
  ' <<<"$mptunnel_release" >/dev/null

mptunnel_asset="$(
  gh api "repos/${mptunnel_repository}/releases/assets/${mptunnel_asset_id}"
)"
jq -e \
  --argjson id "$mptunnel_asset_id" \
  --arg name "$mptunnel_asset_name" \
  --arg digest "$mptunnel_digest" '
    .id == $id and .name == $name and .digest == $digest and
    (.url | endswith("/releases/assets/" + ($id | tostring)))
  ' <<<"$mptunnel_asset" >/dev/null
jq -e --argjson asset_id "$mptunnel_asset_id" '
  ([.assets[] | select(.id == $asset_id)] | length) == 1
' <<<"$mptunnel_release" >/dev/null

mptunnel_ref="$(
  gh api "repos/${mptunnel_repository}/git/ref/tags/${mptunnel_tag}"
)"
mptunnel_type="$(jq -er .object.type <<<"$mptunnel_ref")"
mptunnel_sha="$(jq -er .object.sha <<<"$mptunnel_ref")"
[[ "$mptunnel_type" == tag ]] || {
  echo "MPTUNNEL release no longer resolves through an annotated tag" >&2
  exit 1
}
for _ in 1 2 3 4; do
  case "$mptunnel_type" in
    commit) break ;;
    tag)
      mptunnel_object="$(
        gh api "repos/${mptunnel_repository}/git/tags/${mptunnel_sha}"
      )"
      mptunnel_type="$(jq -er .object.type <<<"$mptunnel_object")"
      mptunnel_sha="$(jq -er .object.sha <<<"$mptunnel_object")"
      ;;
    *)
      echo "unsupported object in MPTUNNEL annotated-tag chain" >&2
      exit 1
      ;;
  esac
done
[[ "$mptunnel_type" == commit && "$mptunnel_sha" == "$mptunnel_commit" ]] || {
  echo "MPTUNNEL tag no longer resolves to the recorded commit" >&2
  exit 1
}

printf 'revalidated frozen MPTUNNEL release_id=%s asset_id=%s tag=%s commit=%s digest=%s\n' \
  "$mptunnel_release_id" "$mptunnel_asset_id" "$mptunnel_tag" \
  "$mptunnel_commit" "$mptunnel_digest"
