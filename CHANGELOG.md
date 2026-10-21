# Changelog

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
