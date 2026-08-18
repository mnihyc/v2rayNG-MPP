#!/usr/bin/env bash
set -euo pipefail

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly script_root
repository_root="$(cd "$script_root/../.." && pwd)"
readonly repository_root

port="${MPTUNNEL_EMULATOR_PORT:-5554}"
serial="emulator-$port"
health_timeout_seconds="${MPTUNNEL_EMULATOR_HEALTH_TIMEOUT_SECONDS:-1200}"
health_poll_seconds="${MPTUNNEL_EMULATOR_HEALTH_POLL_SECONDS:-5}"
health_stable_probes="${MPTUNNEL_EMULATOR_HEALTH_STABLE_PROBES:-3}"
adb_timeout_seconds="${MPTUNNEL_ADB_TIMEOUT_SECONDS:-15}"
emulator_pid=""
health_failure="health probe has not run"

usage() {
  echo "usage: $0 <mptunnel-version|--self-test>" >&2
  exit 2
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
    echo "$name must be a positive integer, got '$value'" >&2
    exit 2
  }
}

adb_read() {
  timeout --signal=TERM --kill-after=5s "${adb_timeout_seconds}s" \
    adb -s "$serial" "$@" 2>/dev/null | tr -d '\r'
}

service_is_ready() {
  local service="$1"
  local result
  if ! result="$(adb_read shell service check "$service")"; then
    health_failure="ADB could not query the $service service"
    return 1
  fi
  if [[ "$result" != *': found' ]]; then
    health_failure="$service service is not published"
    return 1
  fi
}

probe_emulator_health() {
  local state boot animation package_path service

  if ! state="$(adb_read get-state)"; then
    health_failure="ADB transport is unavailable"
    return 1
  fi
  if [[ "$state" != device ]]; then
    health_failure="ADB state is '$state', not 'device'"
    return 1
  fi

  if ! boot="$(adb_read shell getprop sys.boot_completed)"; then
    health_failure="ADB could not read sys.boot_completed"
    return 1
  fi
  if [[ "$boot" != 1 ]]; then
    health_failure="sys.boot_completed is '$boot', not '1'"
    return 1
  fi

  if ! animation="$(adb_read shell getprop init.svc.bootanim)"; then
    health_failure="ADB could not read init.svc.bootanim"
    return 1
  fi
  if [[ "$animation" != stopped ]]; then
    health_failure="boot animation service is '$animation', not 'stopped'"
    return 1
  fi

  for service in activity package input; do
    service_is_ready "$service" || return 1
  done

  if ! package_path="$(adb_read shell pm path android)"; then
    health_failure="Android package manager command failed"
    return 1
  fi
  if [[ "$package_path" != package:* ]]; then
    health_failure="Android framework package is unavailable"
    return 1
  fi

  health_failure="healthy"
}

emulator_process_is_alive() {
  [[ -n "$emulator_pid" ]] && kill -0 "$emulator_pid" 2>/dev/null
}

sleep_between_health_probes() {
  sleep "$1"
}

wait_for_emulator_health() {
  local deadline=$((SECONDS + health_timeout_seconds))
  local attempt=0
  local stable=0

  while ((SECONDS < deadline)); do
    attempt=$((attempt + 1))
    if ! emulator_process_is_alive; then
      echo "emulator process exited before becoming healthy" >&2
      return 1
    fi

    if probe_emulator_health; then
      stable=$((stable + 1))
      echo "emulator health probe $attempt passed ($stable/$health_stable_probes stable)"
      if ((stable >= health_stable_probes)); then
        echo "emulator is healthy after $attempt probes"
        return 0
      fi
    else
      stable=0
      echo "emulator health probe $attempt not ready: $health_failure"
    fi

    sleep_between_health_probes "$health_poll_seconds"
  done

  echo "emulator did not become healthy within ${health_timeout_seconds}s: $health_failure" >&2
  return 1
}

run_self_test() {
  local mock_case
  local mock_probe_count

  adb_read() {
    local request="$*"
    if [[ "$mock_case" == broken-pipe &&
      "$request" == 'shell getprop sys.boot_completed' ]]; then
      return 1
    fi
    case "$request" in
      get-state)
        [[ "$mock_case" == offline ]] && printf 'offline\n' || printf 'device\n'
        ;;
      'shell getprop sys.boot_completed')
        [[ "$mock_case" == booting ]] && printf '0\n' || printf '1\n'
        ;;
      'shell getprop init.svc.bootanim')
        [[ "$mock_case" == animating ]] && printf 'running\n' || printf 'stopped\n'
        ;;
      'shell service check input')
        [[ "$mock_case" == missing-input ]] && \
          printf 'Service input: not found\n' || printf 'Service input: found\n'
        ;;
      'shell service check activity') printf 'Service activity: found\n' ;;
      'shell service check package') printf 'Service package: found\n' ;;
      'shell pm path android')
        [[ "$mock_case" == missing-framework ]] && \
          printf '\n' || printf 'package:/system/framework/framework-res.apk\n'
        ;;
      *)
        echo "unexpected mocked ADB request: $request" >&2
        return 1
        ;;
    esac
  }

  mock_case=healthy
  probe_emulator_health || {
    echo "self-test rejected a healthy emulator: $health_failure" >&2
    return 1
  }
  for mock_case in offline booting animating missing-input missing-framework broken-pipe; do
    if probe_emulator_health; then
      echo "self-test accepted unhealthy case: $mock_case" >&2
      return 1
    fi
  done

  mock_probe_count=0
  emulator_process_is_alive() { return 0; }
  probe_emulator_health() {
    mock_probe_count=$((mock_probe_count + 1))
    health_failure="mock transient failure"
    case "$mock_probe_count" in
      2|4|5|6) return 0 ;;
      *) return 1 ;;
    esac
  }
  sleep_between_health_probes() { SECONDS=$((SECONDS + $1)); }
  health_timeout_seconds=20
  health_poll_seconds=1
  health_stable_probes=3
  SECONDS=0
  wait_for_emulator_health
  [[ "$mock_probe_count" == 6 ]] || {
    echo "self-test did not require three consecutive healthy probes" >&2
    return 1
  }

  probe_emulator_health() {
    health_failure="mock persistent failure"
    return 1
  }
  health_timeout_seconds=3
  SECONDS=0
  if wait_for_emulator_health >/dev/null 2>&1; then
    echo "self-test accepted an emulator that never became healthy" >&2
    return 1
  fi

  emulator_process_is_alive() { return 1; }
  health_timeout_seconds=10
  SECONDS=0
  if wait_for_emulator_health >/dev/null 2>&1; then
    echo "self-test ignored a terminated emulator process" >&2
    return 1
  fi

  echo "emulator health gate self-test passed"
}

show_emulator_log() {
  local emulator_log="$1"
  if [[ -f "$emulator_log" ]]; then
    echo "last emulator log lines:" >&2
    tail -200 "$emulator_log" >&2
  fi
}

cleanup_emulator() {
  local status=$?
  local _
  trap - EXIT

  if [[ -n "$emulator_pid" ]]; then
    timeout --signal=TERM --kill-after=5s 20s adb -s "$serial" emu kill \
      >/dev/null 2>&1 || true
    for _ in {1..20}; do
      if ! kill -0 "$emulator_pid" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if kill -0 "$emulator_pid" 2>/dev/null; then
      kill -KILL "$emulator_pid" 2>/dev/null || true
    fi
    wait "$emulator_pid" 2>/dev/null || true
  fi

  exit "$status"
}

run_instrumentation() {
  local mptunnel_version="$1"
  local avd_name="mptunnel-api35"
  local system_image="system-images;android-35;default;x86_64"
  local avd_home
  local emulator_log
  local -a emulator_args

  [[ "$mptunnel_version" =~ ^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$ ]] || {
    echo "MPTUNNEL version is not stable SemVer: $mptunnel_version" >&2
    exit 2
  }
  for variable in ANDROID_SDK_ROOT HOME RUNNER_TEMP; do
    [[ -n "${!variable:-}" ]] || {
      echo "required environment variable is empty: $variable" >&2
      exit 2
    }
  done
  for command in adb avdmanager sdkmanager tail timeout tr; do
    command -v "$command" >/dev/null || {
      echo "required command is unavailable: $command" >&2
      exit 2
    }
  done
  [[ -x "$ANDROID_SDK_ROOT/emulator/emulator" ]] || {
    echo "Android emulator executable is unavailable" >&2
    exit 2
  }

  require_positive_integer MPTUNNEL_EMULATOR_PORT "$port"
  ((port >= 5554 && port <= 5584 && port % 2 == 0)) || {
    echo "MPTUNNEL_EMULATOR_PORT must be an even port from 5554 through 5584" >&2
    exit 2
  }
  require_positive_integer MPTUNNEL_EMULATOR_HEALTH_TIMEOUT_SECONDS \
    "$health_timeout_seconds"
  require_positive_integer MPTUNNEL_EMULATOR_HEALTH_POLL_SECONDS \
    "$health_poll_seconds"
  require_positive_integer MPTUNNEL_EMULATOR_HEALTH_STABLE_PROBES \
    "$health_stable_probes"
  require_positive_integer MPTUNNEL_ADB_TIMEOUT_SECONDS "$adb_timeout_seconds"

  avd_home="${ANDROID_AVD_HOME:-${HOME}/.android/avd}"
  emulator_log="${RUNNER_TEMP}/mptunnel-emulator.log"
  emulator_args=(
    -port "$port"
    -avd "$avd_name"
    -no-window
    -no-snapshot
    -gpu swiftshader_indirect
    -noaudio
    -no-boot-anim
    -camera-back none
    -camera-front none
  )

  mkdir -p "$avd_home"
  export ANDROID_AVD_HOME="$avd_home"

  echo "installing Android API 35 x86_64 AOSP system image"
  sdkmanager --install "$system_image"
  avdmanager create avd --force \
    --name "$avd_name" \
    --package "$system_image" \
    --abi default/x86_64 \
    --device pixel_6 <<<no
  printf 'hw.cpu.ncore=2\n' >> "$avd_home/$avd_name.avd/config.ini"

  if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    echo "starting emulator with KVM acceleration"
    emulator_args+=(-accel on)
  else
    echo "starting emulator without KVM acceleration"
    emulator_args+=(-accel off)
  fi

  "$ANDROID_SDK_ROOT/emulator/emulator" "${emulator_args[@]}" \
    > "$emulator_log" 2>&1 &
  emulator_pid=$!
  trap cleanup_emulator EXIT

  if ! wait_for_emulator_health; then
    show_emulator_log "$emulator_log"
    return 1
  fi

  export ANDROID_SERIAL="$serial"
  export EMULATOR_PORT="$port"
  cd "$repository_root/V2rayNG"

  # Keep the healthy device untouched until the instrumentation task starts.
  ./gradlew connectedFdroidDebugAndroidTest \
    -PABI_FILTERS=x86_64 \
    -PUNIVERSAL_APK=false \
    -Pandroid.testInstrumentationRunnerArguments.class=com.v2ray.ang.mpp.MptunnelNativeInstrumentedTest \
    "-Pandroid.testInstrumentationRunnerArguments.mptunnelNativeVersion=$mptunnel_version"
}

[[ $# -eq 1 ]] || usage
if [[ "$1" == --self-test ]]; then
  run_self_test
else
  run_instrumentation "$1"
fi
