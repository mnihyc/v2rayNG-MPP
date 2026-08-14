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
  heartbeat, timeout, retention, redundancy-budget, and authentication tuning;
- credential and transport bytes displayed/copied as lowercase hex, with
  explicit **Paste as hex** and **Paste as UTF-8** actions and exact-byte file
  import; pinned certificates remain exact PEM text;
- padded standard Base64 persistence for those three managed values. Base64 is
  reversible encoding, not encryption, so profile backups remain sensitive;
  and
- one syntax-preserving TOML document shared by the guided and raw views. Raw
  edits retain comments and unknown native settings, while app-managed
  placeholders are replaced by inline Base64 references only when MPTUNNEL
  starts—no user-visible material paths or runtime material files are needed.

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
- `initial-srtt-ms=N` (`1..=4294967295`) and `initial-rttvar-ms=N`
  (`0..=4294967295`);
- exactly one of `initial-rate-bps=N`, `initial-rate-kbps=N`,
  `initial-rate-mbps=N` (positive and representable as `u64` bit/s), or
  `initial-rate=unknown|unlimited`; omission means `unknown`;
- `max-datagram-payload-bytes=N` (`512..=65000`) on QUIC only;
- `max-tcp-carriers=N` (`1..=65535`) on TCP only, default `3`. The app's
  default MPTUNNEL resource policy still limits one profile to 64 total carrier
  slots;
- `port-rotation-interval-ms=N` (`5000..=4294967295`) only with a destination
  port range, whose default rotation interval is `300000` ms; and
- `backup`, `expensive`, `allow-bulk`, `control-only`, and `allow-datagrams`,
  each with exactly `=true` or `=false`. Their defaults are respectively
  `false`, `false`, `true`, `false`, and `true`; `allow-datagrams` is TCP-only.

The guided controls expose every option above. Switching transport removes only
options that cannot apply to the selected transport.

### Generated DNS policy

A new guided profile renders the canonical DNS schema: `[dns]` selects its
`default` policy, `[[dns.servers]]` defines a DoH server with
`protocol = "doh"`, literal `address`, `tls_name`, and HTTP `path`, and
`[[dns.policies]]` selects that named server. Family, encryption requirement,
ordered strategy, answer CIDRs, query limits, and cache limits are grouped in
the policy. If `[dns]` is omitted, MPTUNNEL synthesizes the OS `system` server
and `default` policy. Advanced raw TOML can replace or extend that single
authoritative document with other native DNS servers, policies, selection
rules, and named override-record or synthetic-capture definitions attached to
the intended policies with `override_records` or `synthetic_capture`.

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
