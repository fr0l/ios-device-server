# Changelog

## Unreleased — native build and dependency upgrades

- The native executable writes rolling plain-text and JSON (logstash) logs: reflection metadata now covers
  `RollingFileAppender`, `SizeAndTimeBasedRollingPolicy`, `TimeBasedRollingPolicy` and the
  `LoggingEventCompositeJsonEncoder` providers. Before, such a logback config logged nothing in the native image.
  Prefer `SizeAndTimeBasedRollingPolicy` over the deprecated `SizeAndTimeBasedFNATP`.
- Heap dumps, JFR and thread dumps are built in only with `-PnativeDebug` (`--enable-monitoring=heapdump,jfr,threaddump`);
  they made the executable 6.4 MB larger. A default build exits on `-XX:+HeapDumpOnOutOfMemoryError` or
  `-XX:StartFlightRecording=...` ("Could not find option"), so launch scripts must not pass them.
  `-PnativeImageArgs="..."` appends native-image options.
- The native image is optimised for size (`-Os`) and drops `AddAllCharsets`, which okhttp's `native-image.properties`
  turned on: 57 MB instead of 78 MB (22 MB gzipped). Startup (about 1.6 s), memory and HTTP throughput stay within
  run-to-run noise of the previous build.
- Video recording still stops ffmpeg with SIGINT so it finalises the file, but `kill(2)` is now called through
  `java.lang.foreign` (`util/Posix`) instead of nuprocess' internal JNA binding; `nuprocess` and `jna` are gone.
  No process is spawned to send the signal. The jar manifest and the native image enable native access for it.
- iproxy, socat, ffmpeg, idevicesyslog, idevicecrashreport and go-ios are looked up in `PATH`, then in
  `/opt/homebrew/bin` and `/usr/local/bin` (`util/Executables`); before, they were expected in `/usr/local/bin`
  only, which Apple Silicon Homebrew does not use. A missing tool fails with its `brew install` command.
  ffmpeg is looked up on the first recording, so simulators work on hosts without it.
- The native executable is `ios-device-server` and registers `kotlin.collections.EmptyMap` for reflection, which
  `GET /status/health` needs.
- Requests choose a simulator or a real device with `device_type`; on Apple silicon `arch` cannot tell them apart.
  `arch` is gone from the desired capabilities and ignored when sent, like any unknown field (`headless`), and
  from the device `info` in responses (simulators always reported `x86`).
  Without `device_type` the server goes by the UDID's shape (a UUID is a simulator), otherwise a simulator.
- One host serves simulators and real devices together (`simulators` and `devices` in one config); `/status` labels
  each node `<host> (simulators)` / `<host> (devices)`. iproxy and socat are checked by the devices node:
  without them only real devices are unavailable, and a failing device registration no longer stops startup.
- go-ios is no longer used: connected devices are listed with `xcrun devicectl list devices`, which also lists this
  Mac's simulators (skipped) and paired devices that are not attached by USB (not used).
- The server exits when startup fails instead of hanging without listening.
- New top-level `ci` flag, set by the CI deployment. CI is strict: base simulators must exist, and an empty
  `devices.configured` uses every attached device. Without it (laptops) a missing base simulator is created on the
  first clone request, and only `devices.configured` devices are used, so a phone plugged in to charge is ignored.
- Simulators use the iOS runtime of the requested version; tvOS/watchOS/visionOS runtimes have the same numbers and
  were picked before.
- `simctl listapps` output (an old-style plist) is converted with `plutil`; starting the instrumentation failed on it.
- The native image includes the `.xctestrun` templates; creating a simulator failed without them.
- No attached real device is no longer logged as a warning three times a minute.
- Request logging no longer colours its lines, which loaded jansi's native library and failed in the native image.
- The build version no longer has a timestamp: it is `2.0-SNAPSHOT` from `gradle.properties`; CI sets the release
  with `-Pversion=...`. The jar is `build/libs/device-server-<version>.jar`.
- GraalVM CE 25.4 (JDK 25.0.4, `graalvm-community-25.0.4.1.1` in `mise.toml`), Gradle 9.8.2, Kotlin
  2.4.21, Ktor 3.6.0, logback 1.6.5, native-build-tools 1.1.16 and minor updates to the rest.

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
- Fleet signals in the `process` group: `process_memory_mb` (resident memory of the whole process, unlike the heap
  percentage) and `process_cpu_percent` from `ps` in the background; `health_refresh_age_seconds`, which degrades
  and then fails when the background checks stop updating; `simulator_boots_succeeded_total` and
  `simulator_boots_failed_total` (Prometheus can `rate()` them), `simulator_boot_failures` among the last 10 boots
  (degraded at 1, failing above 4) and `simulator_boot_seconds`, their average successful boot time.
  New thresholds: `process_memory_mb`, `process_cpu_percent`, `simulator_boot_failures`, `simulator_boot_seconds`.
- Network speed test on the server port, for the hub, which reaches the Macs from GCP only on TCP 4567 (no auth, no
  side effects, at most 2 at a time, else 429): `GET /status/speedtest?bytes=N` streams N random bytes
  (`application/octet-stream`, `Cache-Control: no-store`, 64 KiB chunks; default 8 MiB, max 32 MiB, else 400);
  `POST /status/speedtest` reads the body without keeping it (max 32 MiB, else 413) and answers
  `{"bytes": N, "millis": M}`, the server-side receive time. Both run on `Dispatchers.IO`. Listed on `GET /`.
- `GET /status/resources` (no auth, like `/status/health`), measured on every request in under 1 ms for the hub's
  memory and CPU charts: `memory_used_percent` (free plus inactive pages count as available, as in
  `memory_available_percent`), `memory_pressure` (`normal` | `warn` | `critical`), `swap_used_gb`, `load_1m`,
  `load_5m`, `load_15m`, `cores`, and `cpu_user_percent` (including nice), `cpu_system_percent`, `cpu_idle_percent`
  over the interval since the previous request. The CPU values are null on the first request and when the kernel
  accounted no ticks since the previous one. Read in-process with `sysctlbyname` and Mach `host_statistics`
  (`java.lang.foreign` downcalls, registered in the native-image metadata); no utility is spawned.
- Metrics for sizing `simulators.limit` and `concurrent_boots` per hardware class, in `GET /status/health/metrics`:
  - `device_server_host_info{chip, performance_cores, efficiency_cores, memory_gb, macos, xcode} 1`;
  - histogram `device_server_simulator_boot_seconds{boots_in_flight, running}` (successful boots, simctl boot to
    bootstatus) and counter `device_server_simulator_boots_total{result="ok|timeout|failed", boots_in_flight}`;
    `timeout` is a boot that failed after the 180 s bootstatus limit;
  - histogram `device_server_agent_status_seconds{result, running}`: the DeviceAgent/WebDriverAgent health check
    each simulator already runs every 60 s;
  - health checks `host_cpu_busy_percent`, `host_cpu_user_percent`, `host_cpu_system_percent` (between background
    refreshes) and `simulators_memory_gb`, `simulator_memory_gb_each` (average per booted simulator),
    `simulator_memory_gb_max` (largest, with its UDID): physical footprint (Activity Monitor's Memory, via
    `proc_pid_rusage`) of each booted simulator's `launchd_sim` and all its descendants. RSS would count the
    runtime's shared libraries in each of ~270 processes per simulator (30 GB instead of 4.3 GB for one iPhone 11).
  `running` is the number of simulators the server holds; labels stay low-cardinality (0 to the limit).
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
