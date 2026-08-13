# Progress

## 2026-08-12 — Reproducible native packaging

- Category: build
- Status: implemented
- Content:
  - Added `./build-mptunnel.sh`, which stages the sibling MPTUNNEL Rust cdylib for all four Android ABIs through MPTUNNEL's pinned API-24/16-KiB build script.
  - Added the same MPTUNNEL checkout, Rust-target setup, and native build step to the APK workflow so clean CI builds cannot ship the MPP UI without its engine.
- Evidence:
  - All four locally staged libraries export the seven expected `Java_com_v2ray_ang_mpp_MptunnelNative_*` entry points and have 16-KiB-compatible LOAD alignment.

## 2026-08-12 — Android MPP profile/editor boundary

- Category: profile model and UI
- Status: implemented and compile-tested
- Content:
  - Added `EConfigType.MPP` and a nested `MppProfileConfig` persisted with each profile.
  - Added manual create/edit routing and an MPP editor for structured TCP/UDP paths and full raw TOML.
  - Credential secret, pinned certificate PEM, and transport secret are first-class content values with paste, show/hide, copy, and content-import controls. No runtime file path is stored or exposed.
  - Added pre-save validation for protocol IDs, path/port limits, credential length, a single pinned PEM certificate, exact 32-byte optional transport material, and managed raw-template tokens.
- Evidence:
  - `cd ./V2rayNG && ./gradlew testFdroidDebugUnitTest --tests com.v2ray.ang.mpp.MppConfigRendererTest` passes.
  - `cd ./V2rayNG && ./gradlew assembleFdroidDebug` passes with the concurrent native/service integration included.
  - The full unit-test task compiles the app and MPP additions but has three non-MPP host-test failures in `UtilsTest` and `AppPickerViewModelTest`.

## 2026-08-12 — MPTunnel TOML compatibility contract

- Category: runtime handoff
- Status: renderer complete; native/service handoff in progress
- Content:
  - Structured TOML uses one loopback `mixed` inbound, matching v2rayNG's shared SOCKS5/HTTP port and retaining SOCKS5 UDP association support.
  - Direct encrypted DoH with a literal bootstrap is used instead of system DNS for the catch-all Android VPN runtime. Its native socket uses the same Android protect callback, and avoiding an MPP egress here also avoids a DNS dependency cycle when the MPP endpoint is a hostname.
  - Secrets and certificate content never enter TOML. Opaque managed tokens are resolved only by the native app-private material boundary.
  - Existing local-proxy username/password settings map to a top-level MPTunnel `[[local_users]]` record and the mixed inbound's named `local_users` binding.
- Contract:
  - Runtime renderer: `MppConfigRenderer.renderRuntime(profile, socksPort, proxyUsername, hasProxyPassword)`.
  - Material tokens: `@mptunnel-profile-credential@`, `@mptunnel-profile-certificate@`, `@mptunnel-profile-transport-secret@`, and optional `@mptunnel-local-proxy-password@`.
  - Raw-template tokens for app-owned substitutions: `@mptunnel-socks-port@`, `@mptunnel-local-user-definition@`, and `@mptunnel-local-user-binding@`.

## 2026-08-13 — MPTUNNEL lifecycle ownership and root HEV credentials

- Category: Android service lifecycle and local-proxy authentication
- Status: implemented and focused-test verified
- Content:
  - A timed-out native MPTUNNEL stop now retains its active engine/config, owning service, HEV bridge, LAN sharing, and VPN TUN so explicit or watchdog-triggered teardown can retry. Successful shutdown and all Xray teardown ordering remain unchanged.
  - A generation- and owner-guarded watchdog tears down the owning service after a terminal native state, with a bounded grace period and retry for a stuck `stopping` state.
  - Root-mode HEV YAML now uses the same safe single-quote escaping as VPN mode and rejects credential CR/LF instead of admitting YAML structure injection.
- Evidence:
  - `cd ./V2rayNG && ./gradlew compileFdroidDebugKotlin testFdroidDebugUnitTest --tests com.v2ray.ang.mpp.MptunnelRuntimeWatchdogPolicyTest --tests com.v2ray.ang.root.RootProxyManagerTest -PABI_FILTERS=x86_64` passes.
  - `git diff --check` passes.

## 2026-08-13 — Native workspace cleanup and pinned source reproduction

- Category: lifecycle cleanup and build reproducibility
- Status: implemented and verified
- Content:
  - Every central MPP profile deletion path removes any stale app-private native workspace while preserving ordinary profile deletion if JNI is unavailable.
  - CI checks out the immutable MPTUNNEL `v0.2.5` base and applies `./patches/mptunnel-android-v1.patch`; local sibling development can continue using `./build-mptunnel.sh` directly.
- Evidence:
  - Android Kotlin compilation passes after cleanup integration.
  - The pinned patch passes `git apply --check` in a clean detached `v0.2.5` worktree.

## 2026-08-13 — Final four-ABI build and emulator acceptance

- Category: release verification
- Status: complete
- Content:
  - Rebuilt the final combined MPTUNNEL cdylib for all four supported Android
    ABIs, then assembled aligned and signed fdroid debug split and universal
    APKs containing the matching native engine.
  - Exercised the actual x86_64 cdylib on an Android 36 emulator for five
    complete authenticated start/SOCKS5/HTTP/stop/cleanup generations.
  - Installed the final x86_64 APK and cold-launched the main activity without
    a fatal exception or native-link failure.
  - Installed the requested Android 36 Google APIs arm64-v8a system image and
    created `MPP_ARM64_API_36`; QEMU2 rejects booting that ARM guest on this
    x86_64 host, so the device acceptance run used the host-compatible image.
- Evidence:
  - Focused MPP and root-credential JVM tests pass.
  - `connectedFdroidDebugAndroidTest` passes the five-generation real-native
    lifecycle and mixed-inbound acceptance test.
  - `assembleFdroidDebug`, APK signature verification, and 16-KiB zip alignment
    checks pass for every output.
  - The final native libraries export the seven expected MPTUNNEL JNI methods
    and have 16-KiB-compatible ELF LOAD alignment.

## 2026-08-13 — Arbitrary MPP path persistence contract

- Category: profile model compatibility
- Status: implemented and focused-test verified
- Content:
  - Added an exact native carrier-path value (`name` plus complete `endpoint` URI) and a nullable explicit path list to the persisted MPP profile.
  - A missing list remains the migration marker for legacy TCP/UDP fields; an explicit empty list stays distinct, and legacy synthesis preserves the existing path names and endpoint formatting.
  - MMKV-equivalent Gson storage and `ServerUiState` save/restore retain arbitrary path URIs without touching the current editor, renderer, or validator.
- Evidence:
  - `cd ./V2rayNG && ./gradlew :app:testFdroidDebugUnitTest --tests 'com.v2ray.ang.mpp.Mpp*Test' -PABI_FILTERS=x86_64` passes.

## 2026-08-13 — Arbitrary-path summaries and safe batch probing

- Category: Android profile integration
- Status: implemented and focused-test verified
- Content:
  - Explicit-path profiles derive their compatibility summary host and port from the first native-parser-valid path, without depending on stale top-level address fields; legacy profiles retain their prior summary behavior.
  - Batch ping probes the first fixed-port TCP path. Raw TOML, ranged-TCP-only, and UDP-only profiles report untested (`0`); structurally malformed explicit path lists report failure (`-1`).
  - MPP basic editor validation still requires remarks but no longer requires the hidden top-level address or port, allowing raw TOML to be the endpoint authority.
- Evidence:
  - `cd ./V2rayNG && ./gradlew :app:testFdroidDebugUnitTest --tests 'com.v2ray.ang.mpp.Mpp*Test' --tests com.v2ray.ang.service.MppTcpProbeSelectorTest -PABI_FILTERS=x86_64` passes.
  - `git diff --check` passes.

## 2026-08-13 — Expert arbitrary-path editor and runtime tuning

- Category: Android MPP profile UI and native configuration parity
- Status: implemented, JVM-tested, and emulator-verified
- Content:
  - Replaced the fixed one-TCP/one-QUIC structured profile with an ordered native
    path list. Profiles may add, duplicate, delete, and reorder up to the native
    64-carrier-slot limit, with independent TCP or QUIC hosts, IPv4/IPv6
    addresses, fixed ports, and port ranges.
  - Every current native carrier URI option is editable: source binding, port
    hopping, TCP carrier maximum, initial SRTT/jitter/rate, datagram payload
    limit, backup/metered, bulk, probe-only, and no-UDP policy. The complete
    endpoint URI remains an authoritative lossless expert field.
  - Incremental invalid text such as a partially typed range or IP address stays
    editable; strict native-compatible validation still runs before Save.
  - Added structured native tuning for path probe cadence/deadline, optional
    traffic budget, authentication freshness, session retention, and TCP/QUIC
    liveness timers. A nullable nested model preserves old Gson/MMKV profiles.
  - New profiles start with valid editable example paths; existing legacy and
    explicit raw-TOML profiles retain their prior authority and migration rules.
- Evidence:
  - `cd ./V2rayNG && ./gradlew :app:testFdroidDebugUnitTest --tests 'com.v2ray.ang.mpp.*' --tests 'com.v2ray.ang.ui.server.MppEndpointUriEditorTest' --tests 'com.v2ray.ang.service.MppTcpProbeSelectorTest' -PABI_FILTERS=x86_64` passes.
  - Android 36 instrumentation seeded and opened a three-path expert profile;
    the real editor displayed TCP range, QUIC, IPv6 backup, every path-option
    control, the complete URI, and custom runtime tuning. Saving through the UI
    passed validation and returned to the profile list.
  - A fresh Add MPP flow was smoke-tested and exposed editable
    `server.example.com` path cards instead of an empty-host raw repair state.
  - `cd ./V2rayNG && ./gradlew :app:assembleFdroidDebug` produces split APKs for
    all four ABIs plus a universal APK, each packaging `libmptunnel.so`.
  - `git diff --check` passes.

## 2026-08-13 — Migration-safe expert MPP tuning contract

- Category: native profile model and TOML rendering
- Status: implemented and focused-test verified
- Content:
  - Added nullable persisted `advanced` tuning so profiles created before the
    expert controls remain distinguishable and keep the prior renderer shape.
  - Exposed the native path-probe timings, reinjection traffic budget,
    authentication freshness, session retention, TCP heartbeat timings, and
    QUIC keep-alive/idle timings with native defaults and bounds.
  - Structured rendering places those values in `[session]`, `[resources]`,
    `[[outbounds]]`, `[outbounds.performance]`, and `[outbounds.security]`
    exactly where MPTUNNEL consumes them. Raw TOML remains authoritative.
  - Added pre-save relationship and boundary validation, including the QUIC
    varint ceiling and the distinct inclusive TCP/exclusive QUIC timeout rules.
- Evidence:
  - `cd ./V2rayNG && ./gradlew testFdroidDebugUnitTest --tests 'com.v2ray.ang.mpp.*' -PABI_FILTERS=x86_64` passes 25 tests with zero failures.
  - `git diff --check` and targeted trailing-whitespace checks pass.

## 2026-08-13T10:26:37+08:00 — Independent v2rayNG-MPP Android identity

- Category: Android release identity and branding
- Status: implemented and targeted-build verified
- Content:
  - Renamed the application and build outputs to `v2rayNG-MPP`, with release
    version `2.3.3-mpp.1` (expected tag `v2.3.3-mpp.1`) and preserved base
    version code `743`.
  - Assigned the independent Play-store application ID `com.v2ray.ang.mpp`;
    the F-Droid flavor resolves to `com.v2ray.ang.mpp.fdroid`. The existing
    `com.v2ray.ang` source namespace remains intact for Kotlin and JNI
    compatibility.
  - Updated flavor shortcut targets, intent-extra namespaces, provider
    authorities through existing `${applicationId}` placeholders, user-agent,
    shared-log name, localized app labels, and Fastlane metadata.
  - Added a small `MPP` badge to every existing legacy and adaptive launcher
    density, a purpose-built themed monochrome layer, the Android TV banner,
    and the Fastlane store icon.
  - Insets the badge independently for circular legacy and adaptive masks, so
    the complete label remains inside the central safe area at every density.
  - Did not build a release APK or app bundle locally; publication remains a
    GitHub Actions responsibility.
- Evidence:
  - `cd ./V2rayNG && ./gradlew :app:processFdroidDebugResources :app:processFdroidDebugMainManifest :app:processPlaystoreDebugResources :app:processPlaystoreDebugMainManifest :app:compileFdroidDebugKotlin -PABI_FILTERS=x86_64 --no-daemon`
    completes successfully.
  - `cd ./V2rayNG && ./gradlew :app:processFdroidDebugAndroidTestManifest -PABI_FILTERS=x86_64 --no-daemon`
    completes successfully.
  - Merged manifests resolve `com.v2ray.ang.mpp` and
    `com.v2ray.ang.mpp.fdroid`, including matching `.androidx-startup` and
    `.cache` authorities; the instrumentation package resolves to
    `com.v2ray.ang.mpp.fdroid.test`.
  - Merged resources resolve `v2rayNG-MPP` / `v2rayNG-MPP (F-Droid)` and the
    separate `ic_launcher_monochrome` resource.
  - Final `processFdroidDebugResources` and `processPlaystoreDebugResources`
    checks pass after visual inspection of mdpi round, adaptive-round, and
    themed-monochrome renders; `git diff --check` remains clean.

## 2026-08-13T11:10:00+08:00 — Immutable two-ABI Android release pipeline

- Category: CI, native supply chain, and signed APK verification
- Status: implemented and statically verified; each run freezes the latest
  stable immutable MPTUNNEL release before consuming its JNI asset
- Content:
  - Replaced the source checkout/patch/Rust JNI build path with exact
    consumption of the Android JNI archive derived from GitHub's latest stable
    immutable MPTUNNEL release. No MPTUNNEL version is pinned in the app.
  - The fetch gate freezes one `/releases/latest` response; requires a stable
    `vMAJOR.MINOR.PATCH` annotated tag; resolves it to a commit; and derives the
    version, archive name, and package root. It verifies GitHub SHA-256 asset
    digests and requires `version.json` to bind the same version, tag, commit,
    unique canonical bundle list, and exact release asset-name inventory.
  - Provenance records the frozen producer version, tag, commit, release ID,
    JNI asset ID/name, and digest. The publisher requeries those exact IDs and
    revalidates immutability, digest, tag, and commit, so a newer latest release
    appearing during an app build cannot change that build's native input.
  - The downloaded archive admits only the documented root, metadata,
    `arm64-v8a`, and `x86_64` libraries. Emulator instrumentation receives the
    resolved version and compares it directly to JNI `nativeVersion()`.
  - Both source libraries and signed-APK copies must have the correct ELF
    machine, at least 16 KiB LOAD alignment, and exactly the seven Kotlin-bound
    MPTUNNEL JNI exports. APK checks also require only the two supported ABIs,
    one universal plus two split outputs, `com.v2ray.ang.mpp.fdroid`, 16 KiB ZIP
    alignment, one common signing certificate, exact staged-library bytes, and
    SHA-256/tag/SHA/version provenance.
  - Branch, pull-request, and manual CI run the complete MPP unit suite plus the
    x86_64 native instrumentation test without signing secrets. Version-tag CI
    preflights all four Android signing secrets, validates the keystore alias,
    signs the exact three release APKs, and uploads only the verified payload.
  - A separate tag-only job has narrowly scoped `contents: write` permission.
    It revalidates tag, commit, bundle ID, inventory, and APK checksums after
    downloading the Actions artifact; creates an owned draft; freshly compares
    all five uploaded assets; and publishes the GitHub Release only afterward.
    A failed draft created by that run is removed without touching any existing
    or already-published release.
  - Removed the obsolete MPTUNNEL source patch artifact. Local source builds
    remain a development convenience; release bundles are Actions-only.
- Evidence:
  - `actionlint .github/workflows/build.yml` passes.
  - Bash parsing and ShellCheck pass for all CI verification scripts; the
    frozen-provenance fixtures cover a later producer version, exact-ID
    revalidation, and digest rejection without consulting `/releases/latest`;
    the shared ELF verifier passes against both locally staged native ABIs.
  - `cd ./V2rayNG && ./gradlew :app:compileFdroidDebugAndroidTestKotlin -PABI_FILTERS=x86_64 -PUNIVERSAL_APK=false --no-daemon`
    succeeds with the dynamic JNI-version instrumentation assertion.
  - `cd ./V2rayNG && ./gradlew help -PABI_FILTERS='arm64-v8a;x86_64' -PUNIVERSAL_APK=true --no-daemon`
    completes successfully, validating the bounded Gradle split configuration
    without assembling a release.
  - `git diff --check` passes for the bounded workflow, scripts, Gradle split
    option, instrumentation version assertion, README, and progress changes.
  - No release APK was assembled, signed, tagged, pushed, or published locally.

## 2026-08-13T11:00:00+08:00 — Independent signing identity and immutable releases

- Category: Android release signing and repository configuration
- Status: configured
- Content:
  - Generated a new fork-specific 4096-bit RSA Android release key rather than
    reusing the upstream application's identity. Recovery material is retained
    under the git-excluded `./.release-secrets/` directory with owner-only
    permissions.
  - Installed exactly the four required repository Actions secrets:
    `APP_KEYSTORE_BASE64`, `APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS`, and
    `APP_KEY_PASSWORD`. Branch and pull-request jobs do not read these secrets.
  - Enabled GitHub immutable releases for the fork. The tag-only publisher
    requires the published release to report `immutable=true` and verifies each
    GitHub asset digest against the Actions-produced bytes.
- Evidence:
  - Signing certificate SHA-256 fingerprint:
    `7F:EE:DA:0C:60:32:20:C4:DB:CA:36:82:C0:D4:43:A1:77:AF:A9:86:48:17:41:AB:A7:34:E9:B7:F9:33:9C:B6`.
  - The authenticated repository secret inventory reports the four expected
    names and no legacy GPG secret.
  - The repository immutable-release API reports `enabled: true`.
