# OpenHerdr — agent guide

Native Android client (Kotlin, Jetpack Compose, Material 3 Expressive) for the herdr socket API, over SSH.
Apache-2.0. Package `dev.cambraia.openherdr`. Single Gradle module `:app`.

## Commands

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # JDK 17 with javac; the local JDK 21 is a JRE
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest                     # integration tests are skipped without OPENHERDR_IT_* env
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Integration tests (real sshd + real herdr)

```sh
scripts/it-sshd.sh /tmp/oh-it 22022 &                 # throwaway unprivileged sshd on 127.0.0.1
herdr --session ohdev server &                         # isolated herdr session
HERDR_SESSION=ohdev herdr workspace create --cwd /tmp --label demo
HERDR_SESSION=ohdev herdr pane run w1:p1 "$PWD/scripts/fake-agent.sh"
scripts/run-it.sh /tmp/oh-it -q                        # must report 4 passed, 0 skipped
```

Clean up afterwards: `herdr --session ohdev server stop && herdr session delete ohdev`, stop the sshd, `rm -rf /tmp/oh-it`.

## Architecture

- `protocol/` — pure Kotlin. `HerdrProtocol` (NDJSON codec, protocol 22), `HerdrClient` (one request per connection;
  `events.subscribe` is the only long-lived connection), `Ansi` (SGR parser for `format: "ansi"` reads).
- `ssh/` — JSch (`com.github.mwiede:jsch`). `SshSession` opens `direct-streamlocal@openssh.com` channels to the herdr
  socket; the home directory comes from the SFTP subsystem, so no shell command ever runs. `PinnedHostKeys` is TOFU
  pinning per `host` / `[host]:port`. `EcdsaIdentity` turns JCA DER signatures into SSH ECDSA blobs.
- `data/` — `ConnectionManager` (connect, subscribe, refresh on events, reconnect with backoff), `AgentAlerts` (pure
  transition diff for notifications), `AppStore` (profiles + pins in one atomic JSON file), `DeviceKey` (Keystore P-256).
- `ui/` — Compose screens; `MainActivity` holds a tiny back stack (`NavViewModel`). `ConnectionService` is a
  `specialUse` foreground service that keeps the link alive while connected.

## Conventions

- herdr facts come from the installed binary: `herdr api schema --json`, `herdr --help`. Fixtures in tests are real
  captured lines; keep them real.
- Events are invalidation signals followed by authoritative reads (`agent.list`, `workspace.list`), never replayed onto state.
- `pane.agent_status_changed` needs a `pane_id`, so the subscription is reopened when the pane set changes.
- Never add server-side components, OTA updates, telemetry or cloud services. Never weaken host key checks.
- Key names for `pane.send_keys`: `enter esc tab shift+tab up down left right backspace ctrl+c` (no `pageup`).
- Material 3 is pinned to 1.5.0-beta01 above the Compose BOM for the Expressive APIs (`MaterialShapes`,
  `LoadingIndicator`, button `shapes`). In 1.5, `RoundedPolygon.toShape()` is `@Composable`; do not wrap it in `remember`.
- Tests ship with the code. Run the mutation spot-checks for security-relevant logic (pinning, signatures).
