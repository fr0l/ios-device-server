# Changelog

## Unreleased — native build and dependency upgrades

- The native executable writes rolling plain-text and JSON (logstash) logs: reflection metadata now covers
  `RollingFileAppender`, `SizeAndTimeBasedRollingPolicy`, `TimeBasedRollingPolicy` and the
  `LoggingEventCompositeJsonEncoder` providers. Before, such a logback config logged nothing in the native image.
  Prefer `SizeAndTimeBasedRollingPolicy` over the deprecated `SizeAndTimeBasedFNATP`.
- The native image is built with `--enable-monitoring=heapdump,jfr,threaddump`, so `-XX:+HeapDumpOnOutOfMemoryError`
  and `-XX:StartFlightRecording=...` work on the executable.
- The build version no longer has a timestamp: it is `2.0-SNAPSHOT` from `gradle.properties`; CI sets the release
  with `-Pversion=...`. The jar is `build/libs/device-server-<version>.jar`.
- GraalVM CE 25.4 (JDK 25.0.4, `graalvm-community-innovation-25.0.4.1.1` in `mise.toml`), Gradle 9.8.2, Kotlin
  2.4.21, Ktor 3.6.0, logback 1.6.5, native-build-tools 1.1.16, nuprocess 4.0.0 and minor updates to the rest.

## Unreleased — host health endpoint

Branch `addObservabilityControls`. `GET /status`, `/status/config` and `/quitquitquit` are unchanged.

- New `GET /status/health` (no auth, always HTTP 200): `schema_version`, `generated_at`, `measured_at`,
  `health` (`ok` | `degraded` | `failing`), `reasons` and `checks[]` of `{name, group, status, value, threshold,
  message, critical, unit, details}`. Groups: `resources` (load per core, memory, swap, disk free on the simulator,
  DerivedData and temp volumes, thermal state), `process` (uptime, build version and git commit, JVM heap and
  threads, simulators in use vs limit, device manager readiness, `simctl list` time, Xcode version), `network`
  (link type, speed and duplex, IP/subnet/VLAN/gateway, gateway ping, and DNS, TCP connect and HTTP first byte for
  each `health.probe_urls`) and `time` (NTP offset).
- Verdict: `failing` if any check fails, `degraded` if any degrades or a critical check is `unknown`. A probe that
  cannot run (missing binary, timeout, unparsable output) reports `unknown` instead of failing the response. A
  network target that does not answer is a measurement and fails its check.
- Requests take milliseconds: system utilities run in the background every `health.refresh_seconds` (20).
- `GET /status/health/metrics`: the same values in Prometheus text format (`device_server_health`,
  `device_server_health_check_status`, `device_server_health_check_value`).
- Config: new optional `health` section (`probe_urls`, `ntp_server`, `expected_subnets`, `expected_xcode_version`,
  `thresholds`), see `config/config.schema.json`. The default `probe_urls` is only `https://storage.googleapis.com/`;
  add your CI server in the host config.

## Unreleased — orphan simulator clones cleanup

Branch `cleanSimulatorClones`. Clones survived a server restart, and a clone whose release failed stayed on disk
(and kept its slot) until someone deleted it by hand.

- On start, and then every 10 minutes, the server deletes clones that no session holds: those in the registry plus
  any simulator named like the server's clones (`<base name> Clone <unix time>`). Base simulators are never deleted.
  Start also runs `simctl delete unavailable`.
- Deleting a clone also removes its temp files, which `simctl delete` leaves behind: `derivedData_<udid>`,
  `xctestRunDir_<udid>`, `web_driver_agent_log_<udid>.txt`, `iOS_SysLog_<udid>[.err].log` in `$TMPDIR` and
  `/private/var/tmp/com.apple.CoreSimulator.SimDevice.<udid>`. The same cleanup deletes these files for any
  simulator UDID that no longer exists. Physical device UDIDs (`00008110-...`) and other files are not touched.
- A release whose `simctl delete` fails still frees the slot and ports; the periodic cleanup retries the delete.

## Unreleased — slim simulators (disabled launchd services)

Branch `fixSimulatorDisableServices`. The server slimmed simulators down by booting them with
`simctl boot <udid> --disabledJob=<label> ...`. Since Xcode 26 these arguments have no effect, so every simulator ran
all ~220 processes (~3 GB). The server now migrates from `--disabledJob` arguments to a plist: before booting it
writes the labels into the simulator's `disabled.plist`, which `launchd_sim` reads at start.

Verified on Xcode 26.5 / iOS 26.5 with the same 5 jobs (`chronod`, `PosterBoard`, `siriknowledged`, `suggestd`,
`corespeechd`) on fresh clones:

- `simctl boot --disabledJob=<label>`: the boot succeeds (exit 0, no warning), and `simctl help boot` still lists
  the flag, but all 5 jobs are running and `launchctl print-disabled` lists none of them.
- `disabled.plist` written before boot: none of the 5 jobs runs, and `launchctl print-disabled` lists all 5 as disabled.

With the full sample list: 219 processes / 2.97 GB → 76 / 0.98 GB per simulator.

Config: `simulators.disabled_services` now takes bare launchd labels (`com.apple.chronod`), not
`--disabledJob=com.apple.chronod`. See `config/singlehost-sample.json` for a list that keeps push, universal links
and photos.

## Unreleased — client ports behind a relay

Branch `portRelay` (on top of `removeFbsimctl`). Only `41800-42800` is open in the firewall, but simulator clones
being created and deleted leaked listening ports until the old `41800-65500` range overflowed within hours.
The device server now owns the public range itself and forwards to host-local ports.

- **client** Simulators: `wda_endpoint`, `calabash_endpoint` and `mjpeg_server_port` use public ports (`41800-42800`,
  recycled round-robin in memory, no `netstat`). WDA/DeviceAgent and the app under test listen on private ports
  (`43000-49000`); the server relays bytes between the two. A client connecting before the target is up gets a reset.
- **client** Simulators: `calabash_port` is now the private port the app under test must listen on
  (what goes into `CalabashServerPort`), no longer the port clients connect to. Always connect via `calabash_endpoint`.
  A client that rebuilds the endpoint as `host:calabash_port` (e.g. the `include?(@calabash_port.to_s)` fallback in
  `RemoteDeviceIos#init_calabash_endpoint`) would bypass the relay and be blocked by the firewall.
- `GET /devices/{ref}/endpoint/{port}` accepts either the public or the private port of a simulator and returns the public URL.
- Physical devices keep their proxies (`iproxy`) but take their client-facing ports from the same public pool
  instead of the old `41800-65500` range.
- Relays are started when the simulator boot begins and closed on release, which returns the public ports immediately.

## Unreleased — single-host server without fbsimctl

Branch `removeFbsimctl`. Entries marked **client** need a change in API consumers (e.g. `client/rb`).

### REST API

- **client** `GET/POST /devices*`: `DeviceDTO` no longer has `fbsimctl_endpoint`. It pointed at a port nothing
  listened on (the server never started `fbsimctl listen`), so `client/rb`'s `DeviceClient` / `FbHttpServerClient`
  (screenshot, open_url, set_location, install/list/launch/terminate/uninstall app) must use the server's own
  `/devices/{ref}/...` routes (`apps`, `location`, `video`, ...) instead of talking to that endpoint.
  `DeviceClient.from_hash` reads `device['fbsimctl_endpoint']` and will now get `nil`. `FbsimctlClientError` is obsolete.
- **client** `GET /devices/{ref}/apps`: items changed from fbsimctl shape to a flat camelCase one.

  | before | after |
  |---|---|
  | `bundle.bundle_id` | `bundleId` |
  | `bundle.name` | `name` |
  | `bundle.path` | `bundlePath` |
  | `bundle.binary` | removed |
  | `data_container` | `dataContainer` |
  | `install_type` | `installType` (lowercase, `"user"` / `"system"`) |

- **client** `POST /nodes/restart_gracefully` removed (no node restart infrastructure).
- `POST /devices`: new optional capability `device_type` (`"simulator"` | `"device"`) to target a type explicitly.
  When omitted, routing falls back to the previous arch-based logic.
- `GET /status`: shape unchanged, but `alive_nodes` / `all_nodes` now only ever list the local host.
- Not changed: `calabash_endpoint`, `calabash_port`, `wda_endpoint`, `mjpeg_server_port`, `state`, `info`, `capabilities`.
  Note `client/rb` also reads `user_ports`, which the server does not return (already out of sync before this change).

### Configuration

- **Breaking:** config file is flat, `nodes[]` is gone (see `config/config.schema.json`, `config/singlehost-sample.json`).
  Old multi-host configs now fail at startup instead of silently starting with no devices.

  ```json
  {
    "timeouts": { "device": 600 },
    "public_host": "optional-override",
    "simulators": { "limit": 10, "concurrent_boots": 2, "disabled_services": [], "shutdown_simulators": false },
    "devices": { "whitelist_apps": [], "uninstall_apps": false, "configured": [{ "udid": "..." }] }
  }
  ```
- `simulators` / `devices` are optional; omit one to disable that type. At least one is required.
- Without `-Ddevice.server.config.path` the server starts with simulators only (defaults).
- Removed JVM properties: `remote.test.helper.app.bundle.path`, `fbsimctl.version`, `useFbsimctlProc`.

### Internals

- Multi-host fleet code removed: `NodeRegistry`, `NodeWrapper`, `NodeRegistrar`, `NodeRestarter`, `ZombieReaper`,
  test helper app, SSH leftovers (`Remote.isReachable`, `SshConnectionException`).
  `DeviceManager` owns the nodes; `HostFactory` creates a `SimulatorsNode` and/or `DevicesNode` sharing one `PortAllocator`.
- fbsimctl replaced by `xcrun simctl` (simulators), `xcrun devicectl` + `go-ios` (physical devices).
  `go-ios` must be on `PATH`. `scripts/build_fbsimctl.sh`, the `facebook/fb` tap and launcher flags are gone.
- Each device now gets 3 ports (WDA, Calabash, MJPEG) instead of 4.
- `AppInfo` moved to `com.badoo.automation.deviceserver.data`.
