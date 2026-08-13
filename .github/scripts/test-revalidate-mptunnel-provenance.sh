#!/usr/bin/env bash
set -euo pipefail

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly script_root
work_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/mptunnel-provenance-test.XXXXXX")"
trap 'rm -rf "$work_root"' EXIT
readonly commit="0123456789abcdef0123456789abcdef01234567"
readonly digest="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

mkdir -p "$work_root/bin"
cat > "$work_root/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == api ]]
endpoint="$2"
printf '%s\n' "$endpoint" >> "$GH_CALL_LOG"
case "$endpoint" in
  repos/mnihyc/mptunnel/releases/700)
    jq -cn --arg digest "sha256:$GH_FIXTURE_DIGEST" '{
      id:700, tag_name:"v0.2.7", draft:false, prerelease:false, immutable:true,
      assets:[{id:701, name:"mptunnel-0.2.7-android-jni.tar.gz", digest:$digest}]
    }'
    ;;
  repos/mnihyc/mptunnel/releases/assets/701)
    jq -cn --arg digest "sha256:$GH_FIXTURE_DIGEST" '{
      id:701, name:"mptunnel-0.2.7-android-jni.tar.gz", digest:$digest,
      url:"https://api.github.com/repos/mnihyc/mptunnel/releases/assets/701"
    }'
    ;;
  repos/mnihyc/mptunnel/git/ref/tags/v0.2.7)
    printf '%s\n' '{"object":{"type":"tag","sha":"89abcdef0123456789abcdef0123456789abcdef"}}'
    ;;
  repos/mnihyc/mptunnel/git/tags/89abcdef0123456789abcdef0123456789abcdef)
    printf '%s\n' '{"object":{"type":"commit","sha":"0123456789abcdef0123456789abcdef01234567"}}'
    ;;
  *)
    echo "unexpected gh endpoint: $endpoint" >&2
    exit 1
    ;;
esac
EOF
chmod 755 "$work_root/bin/gh"

write_provenance() {
  local provenance_digest="$1"
  jq -n \
    --arg commit "$commit" \
    --arg sha256 "$provenance_digest" '{
      mptunnel: {
        version:"0.2.7", tag:"v0.2.7", commit:$commit, release_id:700,
        asset:"mptunnel-0.2.7-android-jni.tar.gz", asset_id:701,
        sha256:$sha256
      }
    }' > "$work_root/provenance.json"
}

export PATH="$work_root/bin:$PATH"
export GH_TOKEN=test-token
export GH_CALL_LOG="$work_root/gh-calls.txt"
export GH_FIXTURE_DIGEST="$digest"

write_provenance "$digest"
"$script_root/revalidate-mptunnel-provenance.sh" "$work_root/provenance.json"
test "$(wc -l < "$GH_CALL_LOG")" = 4
if grep -q '/releases/latest' "$GH_CALL_LOG"; then
  echo "publisher must revalidate frozen IDs, not latest" >&2
  exit 1
fi

: > "$GH_CALL_LOG"
write_provenance "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
if "$script_root/revalidate-mptunnel-provenance.sh" \
  "$work_root/provenance.json" >/dev/null 2>&1; then
  echo "publisher accepted a mismatched asset digest" >&2
  exit 1
fi

echo "MPTUNNEL frozen-provenance fixtures passed"
