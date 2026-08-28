# v2rayNG-MPP

An Android proxy client based on [v2rayNG](https://github.com/2dust/v2rayNG), with
[MPTUNNEL](https://github.com/mnihyc/mptunnel) integrated as an embedded native
engine.

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![GitHub release](https://img.shields.io/github/v/release/mnihyc/v2rayNG-MPP)](https://github.com/mnihyc/v2rayNG-MPP/releases/latest)
[![GitHub downloads](https://img.shields.io/github/downloads/mnihyc/v2rayNG-MPP/total?logo=github)](https://github.com/mnihyc/v2rayNG-MPP/releases)

## Download

Download the signed APKs from the
[v2rayNG-MPP releases page](https://github.com/mnihyc/v2rayNG-MPP/releases).

Release builds are provided for `arm64-v8a`, `x86_64`, and as a universal APK
containing both native ABIs. Their application IDs are:

- `com.v2ray.ang.mpp` for the standard flavor
- `com.v2ray.ang.mpp.fdroid` for the F-Droid flavor

## MPP profiles

This fork adds `EConfigType.MPP` alongside the retained Xray profile types. An
MPP profile uses the embedded MPTUNNEL `cdylib`; it does not substitute a remote
helper process for the native engine.

The MPP editor is intended for users who already understand the protocol. It
supports:

- up to 64 independently ordered TCP and QUIC paths and carrier slots, with
  each full endpoint URI remaining authoritative;
- the complete native endpoint-URI option set plus profile-level path-probe,
  heartbeat, timeout, retention, optional-reinjection-budget, and authentication
  tuning. All user-configured durations use MPTUNNEL v0.4.4's seconds grammar;
- credential and transport bytes displayed/copied as lowercase hex, with
  explicit **Paste as hex** and **Paste as UTF-8** actions and exact-byte file
  import; pinned certificates remain exact PEM text;
- padded standard Base64 persistence for those three managed values. Base64 is
  reversible encoding, not encryption, so profile backups remain sensitive;
- a per-profile MPTUNNEL log level, defaulting to `info`, whose native,
  redacted records are delivered through the app logger and appear in the
  in-app Logcat view (`debug` adds correlated connection routing details);
- an authenticated MPTUNNEL dashboard on `127.0.0.1:7600`. Each newly persisted
  template receives an independent 24-byte random token as 48 lowercase hex
  characters in its authoritative TOML; peer diagnostics remain disabled by
  default; and
- one syntax-preserving TOML document shared by the guided and raw views. Raw
  edits retain comments and unknown native settings, while app-managed
  placeholders are replaced by inline Base64 references only when MPTUNNEL
  starts—no user-visible material paths or runtime material files are needed.

New generated client documents visibly pin the ordinary product envelope:
`[flow]` uses a 300-second payload-idle timeout, a 10% optional reliable
reinjection budget, and 10% sender-side QUIC loss compensation. Global product
admission and local mixed-inbound limits are 4096. Per-outbound performance can
override `[flow]`, while a QUIC path's `loss-compensation-percent` URI value has
the highest precedence for that path.

Android VPN capture remains available to MPP profiles, including per-app
allow/bypass filtering, literal VPN DNS servers, and optional IPv6 TCP/UDP.
Xray-specific routing rules, FakeDNS, and resolver semantics are not translated
into structured MPP profiles; use native raw TOML when those policies are
required.

### Carrier URI grammar

The editor and profile validator use the current native grammar directly:
`tcp://HOST:PORT[-END]` or `quic://HOST:PORT[-END]`. IPv6 literals must be
bracketed. Ports use canonical decimal text in `1..=65535`; a range must be
strictly ascending. Existing profile URIs outside this grammar fail validation
and are never silently rewritten.

Every query item requires an explicit value, duplicate and unknown keys are
rejected, and the exact option vocabulary is:

- `source-address=IP` for a literal local IPv4 or IPv6 address; omission lets
  the OS select the source address;
- `initial-srtt-s=S` (`0.001..=4294967.295`) and `initial-rttvar-s=S`
  (`0..=4294967.295`), expressed at exact millisecond precision;
- exactly one of `initial-rate-bps=N`, `initial-rate-kbps=N`,
  `initial-rate-mbps=N` (positive and representable as `u64` bit/s), or
  `initial-rate=unknown|unlimited`; omission means `unknown`;
- `loss-compensation-percent=P` on QUIC only, where `P` is from `0` through
  `99.9999` with at most four fractional digits. It is sender-side loss-policy
  input, independent of the initial-rate hint;
- `max-datagram-payload-bytes=N` (`512..=65000`) on QUIC only;
- `max-tcp-carriers=N` (`1..=65535`) on TCP only, default `3`. The app's
  default MPTUNNEL resource policy still limits one profile to 64 total carrier
  slots;
- `port-rotation-interval-s=S` (`5..=4294967.295`, at exact millisecond
  precision) only with a destination port range, whose default is `300` s; and
- `backup`, `expensive`, `allow-bulk`, `control-only`, and `allow-datagrams`,
  each with exactly `=true` or `=false`. Their defaults are respectively
  `false`, `false`, `true`, `false`, and `true`; `allow-datagrams` is TCP-only.

The guided controls expose every option above. Switching transport removes only
options that cannot apply to the selected transport.

### VPN DNS ownership

New generated MPTUNNEL documents intentionally contain no active `[dns]`
section. v2rayNG owns Android's VPN DNS setting, and captured DNS requests are
ordinary TCP or UDP proxy traffic. The default literal-delegation rule sends a
configured private resolver such as `10.1.2.3` through `remote-mpp`; the server
then independently authorizes its egress. MPTUNNEL `[dns]` remains available in
advanced TOML only for local routing-resolution policy, such as `route-only` or
`full-resolve`; it does not configure Android VPN DNS.

This Product-traffic path is separate from MPTUNNEL's own carrier and native
egress sockets. Every supported run mode keeps the app process or UID outside
its own traffic path, so those sockets use the underlying network without a
per-socket VPN callback; the strict protected JNI path remains available to a
different embedding that captures its own process.

### Target resolution

New guided profiles use `as-is`: when the local inbound supplies a hostname,
MPTUNNEL delegates that hostname unchanged to the server, which independently
applies the same routing and restricted-address policy. Ordinary Android VPN
capture often receives a literal IP after the application has already used the
system resolver; no routing mode can reconstruct that lost hostname. Literal
IP authorization remains enforced at both client and server boundaries. The
guided selector can instead use `route-only` for local routing evidence or
`full-resolve` to send an authorized literal address.
Profiles whose TOML predates this setting show **Compatibility (unchanged)** and
retain MPTUNNEL's historical demand-driven behavior until the user selects a
mode. Guided changes use the native syntax-preserving editor; advanced/custom
TOML is never regenerated or silently rewritten. Saving advanced TOML checks
only its syntax; managed Android bindings and complete native configuration
validation run when the profile starts.

Generated templates also show a complete, commented V2Fly `geoip:private`
direct outbound and its 21 literal CIDRs. It is opt-in and precedes the active
literal-delegation rule, so users can deliberately bypass those destinations
without changing hostname handling. Editor projection schema 2 is the only
guided v0.4.4 contract. Existing schema-1 TOML remains byte-for-byte authority
in the advanced editor: removed v0.4.3 duration fields and URI options are not
aliased, converted, or regenerated, so native v0.4.4 validation reports the
incompatible key that the operator must update deliberately. The ephemeral
schema-zero runtime fallback remains a separate legacy-material migration and
omits management because it has no persisted document in which to expose a
fresh authentication token.

## Release integrity

Release APKs are built, signed, checked, and published only by
[GitHub Actions](https://github.com/mnihyc/v2rayNG-MPP/actions). The workflow does
not pin an MPTUNNEL version. For each build it resolves the latest stable,
immutable MPTUNNEL release, freezes its release/tag/commit identity, and verifies
its release manifest and GitHub SHA-256 digests. From that one frozen release it
consumes the ordinary `android-arm64` and `android-x86_64` platform archives,
embedding respectively the `arm64-v8a` and `x86_64` `libmptunnel.so`. Each exact
archive inventory, ELF properties, and JNI exports is checked before embedding;
the same native checks are repeated against the signed APKs before publication.

## GeoIP and GeoSite data

`geoip.dat` and `geosite.dat` are stored under the app-specific assets directory:

- `Android/data/com.v2ray.ang.mpp/files/assets`
- `Android/data/com.v2ray.ang.mpp.fdroid/files/assets`

The exact external-storage path can vary by device. The in-app download uses the
enhanced data from [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
and requires a working proxy. Data from
[Loyalsoldier/geoip](https://github.com/Loyalsoldier/geoip) or a compatible
third-party `.dat` source can also be imported manually.

## Upstream

v2rayNG-MPP is a fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG) and
retains its Xray integration and general Android client behavior. Refer to the
[upstream wiki](https://github.com/2dust/v2rayNG/wiki) for shared v2rayNG usage
and routing documentation; MPP-specific behavior is documented in this fork.
