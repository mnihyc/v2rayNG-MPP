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

- any number of independently ordered TCP and QUIC paths, with each full
  endpoint URI remaining authoritative;
- the complete native endpoint-URI option set plus profile-level path-probe,
  heartbeat, timeout, retention, redundancy-budget, and authentication tuning;
- secrets, pinned certificates, and transport material pasted or imported as
  content—the app manages private runtime files internally and never exposes
  their paths in the profile UI; and
- raw TOML editing for native features beyond the structured controls,
  including custom MPTUNNEL DNS and routing plans.

Android VPN capture remains available to MPP profiles, including per-app
allow/bypass filtering, literal VPN DNS servers, and optional IPv6 TCP/UDP.
Xray-specific routing rules, FakeDNS, and resolver semantics are not translated
into structured MPP profiles; use native raw TOML when those policies are
required.

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
