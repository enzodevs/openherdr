# OpenHerdr — agent guide

Native Android client (Kotlin, Jetpack Compose, Material 3 Expressive) for the herdr socket API, over SSH.
Apache-2.0. Package `dev.cambraia.openherdr`. Single Gradle module `:app`.

## Commands

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # JDK 17 with javac; the local JDK 21 is a JRE
scripts/fetch-sherpa.sh                              # once: sherpa-onnx AAR into app/libs (SHA-256 pinned, not committed)
./gradlew :app:testDebugUnitTest                     # integration tests are skipped without OPENHERDR_IT_* env
./gradlew :app:assembleRelease                       # R8 + baseline profiles; debug builds stutter in Compose
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release signing reads `~/.config/openherdr-release/keystore.properties` (or `$OPENHERDR_KEYSTORE_PROPERTIES`); never
commit it. Without that file the release build is signed with the debug key. Android refuses to update an app across
signing keys, and reinstalling deletes the Keystore device key, so a phone running a debug-signed build keeps getting
debug-signed builds: `OPENHERDR_KEYSTORE_PROPERTIES=/none ./gradlew :app:assembleRelease`.

### Speech model (dictation)

Parakeet TDT 0.6B v3 int8 (`sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2` from the sherpa-onnx `asr-models`
release, ~490 MB) is not bundled. Open the app once, then push `encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx
tokens.txt` to `/sdcard/Android/data/dev.cambraia.openherdr/files/models/parakeet-tdt-0.6b-v3-int8/`. Directories
created by `adb` are `drwxrws---` owned by `shell`, which the app cannot enter: `chmod 771` them afterwards.

### Integration tests (real sshd + real herdr)

```sh
scripts/it-sshd.sh /tmp/oh-it 22022 &                 # throwaway unprivileged sshd on 127.0.0.1
herdr --session ohdev server &                         # isolated herdr session
herdr --session ohdev workspace create --cwd /tmp --label demo --no-focus
herdr --session ohdev pane run w1:p1 "$PWD/scripts/fake-agent.sh"
scripts/run-it.sh /tmp/oh-it -q                        # must report 4 passed, 0 skipped
```

Always pass `--session ohdev` to each CLI call: the `HERDR_SESSION` environment variable is not honored and the command
lands in the user's real session. Clean up afterwards: `herdr --session ohdev server stop && herdr session delete ohdev`, stop the sshd, `rm -rf /tmp/oh-it`.

## Architecture

- `protocol/` — pure Kotlin. `HerdrProtocol` (NDJSON codec, protocol 22), `HerdrClient` (one request per connection;
  `events.subscribe` is the only long-lived connection), `Ansi` (SGR parser for `format: "ansi"` reads).
- `ssh/` — JSch (`com.github.mwiede:jsch`). `SshSession` opens `direct-streamlocal@openssh.com` channels to the herdr
  socket; the home directory comes from the SFTP subsystem, so no shell command ever runs. `PinnedHostKeys` is TOFU
  pinning per `host` / `[host]:port`. `EcdsaIdentity` turns JCA DER signatures into SSH ECDSA blobs.
- `data/` — `ConnectionManager` (connect, subscribe, refresh on events, reconnect with backoff), `AgentAlerts` (pure
  transition diff for notifications), `AppStore` (profiles + pins in one atomic JSON file), `DeviceKey` (Keystore P-256).
- `data/Topology` — pure workspace → tab → pane tree from `workspace.list`, `tab.list`, `pane.list` and, for panes
  without an agent, `pane.process_info` (foreground command, null at the shell prompt). Plain terminals are first-class:
  they open in the same terminal view and accept commands (send_text + Enter, which is all `herdr pane run` does).
  Creates use `focus: false` so the PC keeps its focus; closes always go through a confirmation that lists what ends.
- `transcript/` — chat view of agent sessions. herdr integrations report `agent_session` (Claude Code and Codex: an
  id; Pi: a path). `TranscriptLocator` finds the JSONL over SFTP (Claude: `~/.claude/projects/<cwd with
  non-alphanumerics as '-'>/<id>.jsonl`; Codex: UUIDv7 id → `~/.codex/sessions/YYYY/MM/DD/rollout-*-<id>.jsonl`),
  `TranscriptTail` follows it, `ChatLog` parses each format. Fixtures are real lines with text replaced.
- `voice/` — `Dictation`: AudioRecord 16 kHz PCM16 appended to `filesDir/dictation/take.pcm` as it is recorded, so a
  take survives a killed process; pause/resume extend the same take, capped at 10 minutes. `finish` cuts it at pauses
  into ~30 s pieces (`AudioSegments.split`) for the sherpa-onnx `OfflineRecognizer` (`nemo_transducer`). A take that
  fails stays on disk as `Failed` (retry, save as WAV, discard); audio is deleted only after its text was delivered.
  The recognizer loads while the user speaks and is released on `TRIM_MEMORY_UI_HIDDEN`.
- `data/Drafts` — one unsent prompt per `<profile id>/<pane id>` in SharedPreferences. Dictation appends to it; it is
  cleared only after the server accepted the send. Attachments are uploaded over SFTP to `/tmp/openherdr-<user>/`
  (0700, files 0600) and referenced in the draft by path.
- `ui/` — Compose screens. `HomeScreen` hosts three tabs (Workspaces, Servers, Settings) under a floating `GlassTabBar`
  (Kyant Backdrop: lens + blur on API 33+, blur on 31–32, solid below). `NavViewModel` is a tiny back stack over it.
  Leaving the Workspaces tab or the app does not disconnect; only the power button or the notification does.
  `ConnectionService` is a `specialUse` foreground service that keeps the link alive while connected.

## Conventions

- herdr facts come from the installed binary: `herdr api schema --json`, `herdr --help`. Fixtures in tests are real
  captured lines; keep them real.
- Events are invalidation signals followed by authoritative reads (`agent.list`, `workspace.list`, `tab.list`,
  `pane.list`), never replayed onto state. `ConnectionManager.change {}` refreshes right after the app's own changes.
- Claude Code records a prompt typed mid-turn only as `attachment.type = "queued_command"` (`origin.kind = "human"`),
  never as a `user` line; `ChatLog` shows those and skips `task-notification` ones.
- `pane.agent_status_changed` needs a `pane_id`, so the subscription is reopened when the pane set changes.
- Never add server-side components, OTA updates, telemetry or cloud services. Never weaken host key checks.
- Key names for `pane.send_keys`: `enter esc tab shift+tab up down left right backspace ctrl+c` (no `pageup`).
- Material 3 is pinned to 1.5.0-beta01 above the Compose BOM for the Expressive APIs (`MaterialShapes`,
  `LoadingIndicator`, button `shapes`). In 1.5, `RoundedPolygon.toShape()` is `@Composable`; do not wrap it in `remember`.
- Visual identity "Graphite" (`ui/Theme.kt`): always dark, near-white primary, color only for agent status. The
  neutrals are one OKLCH hue (270) at chroma 0.006, stepped in lightness only (page L 0.205, each surface ~0.035
  lighter); every text color keeps WCAG AA (4.5:1) on the surfaces it sits on. Derive new steps in OKLCH, not by eye.
- Home hierarchy: tabs are the prominent sections, the workspace is a quiet label above them; both fold. New panes
  are `pane.split` on the tab's last pane with `focus: false`.
- Session files hold whole messages, so the chat cannot stream: new replies wipe in (`Modifier.reveal`) and a
  "Working" row shows while the agent's status is working. Unbounded for headings, Geist for text, Geist Mono for code (bundled, OFL, `licenses/`).
- Tests ship with the code. Run the mutation spot-checks for security-relevant logic (pinning, signatures).
