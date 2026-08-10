# bambu-live-automation

Automates live streaming of Bambu Lab prints using [`bambu-cloud-kotlin`](https://github.com/nachidel/bambu-cloud-kotlin), Wake-on-LAN and, progressively, OBS.

> This project is unofficial and is not affiliated with, endorsed by, or supported by Bambu Lab.

## Goal

`bambu-live-automation` is intended to run continuously on a Raspberry Pi and react to Bambu Lab printer events.

The long-term workflow is:

```text
Bambu Cloud
    │
    ▼
bambu-cloud-kotlin
    │
    ▼
bambu-live-automation
    │
    ├── detect print preparation / start
    ├── wake the studio PC with Wake-on-LAN
    ├── wait until the PC and OBS are ready
    ├── start the livestream
    ├── keep the livestream running during printer pauses
    └── stop the livestream when the print finishes or fails
```

The project is intentionally built incrementally so every automation step can be tested independently.

## Current status

Implemented:

- Bambu Cloud connection through `bambu-cloud-kotlin`
- printer lifecycle event handling
- startup/reconnection state reconciliation
- print start deduplication
- pause / resume handling
- finish / failure handling
- interactive Bambu event simulator
- Wake-on-LAN support
- colored console logging with SLF4J + Logback
- simulation mode that does not require a real print

Planned:

- studio PC reachability monitoring
- OBS WebSocket connection and readiness checks
- automatic OBS / livestream start
- automatic livestream stop
- YouTube live integration
- presence/activity safeguards
- safe studio PC shutdown

## Requirements

- JDK 21
- Gradle
- a Bambu Lab account access token
- access to the GitHub Package containing `bambu-cloud-kotlin`
- a Wake-on-LAN capable studio PC for WOL automation

The project currently uses:

```text
com.nachidel:bambu-cloud-kotlin:0.1.0
```

from:

```text
https://maven.pkg.github.com/nachidel/bambu-cloud-kotlin
```

## GitHub Packages authentication

GitHub Packages requires authentication.

Add the following values to your local Gradle configuration:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_TOKEN
```

Recommended location:

```text
~/.gradle/gradle.properties
```

The token must have at least:

```text
read:packages
```

Never commit these credentials to the repository.

## Configuration

Runtime configuration is provided using environment variables.

### Bambu Cloud

```text
BAMBU_TOKEN=...
```

Required in real mode.

### Simulation mode

```text
BAMBU_SIMULATION=true
```

When enabled, no Bambu Cloud connection is made.

The simulator generates the same public `BambuEvent` objects consumed by the real automation controller.

### Wake-on-LAN

```text
WOL_ENABLED=true
STUDIO_PC_MAC=AA-BB-CC-DD-EE-FF
WOL_BROADCAST=192.168.1.255
WOL_PORT=9
```

`WOL_ENABLED` defaults to `false`.

This allows the simulator to run safely without waking the studio PC unless Wake-on-LAN is explicitly enabled.

Example safe simulation configuration:

```text
BAMBU_SIMULATION=true
WOL_ENABLED=false
```

Example simulation with a real Wake-on-LAN test:

```text
BAMBU_SIMULATION=true
WOL_ENABLED=true
STUDIO_PC_MAC=AA-BB-CC-DD-EE-FF
WOL_BROADCAST=192.168.1.255
WOL_PORT=9
```

Do not commit real tokens or local machine configuration.

## Simulator

The simulator makes it possible to develop and test the automation without launching a real print.

Available commands:

```text
p = prepare
s = start
a = pause
r = resume
f = finish
x = failed

i = application starts while a print is already running
0 = initial FINISHED snapshot
c = complete print scenario

q = quit
```

### Complete scenario

Entering:

```text
c
```

simulates:

```text
PREPARING
    ↓
PRINTING
    ↓
PAUSED
    ↓
PRINTING
    ↓
FINISHED
```

The automation should request startup only once for the entire print.

## Lifecycle behaviour

The automation does not rely only on transition events.

This is important because the application may start or reconnect while the printer is already printing.

For example, the first received state may be:

```text
PrinterStatusChanged(PRINTING)
```

without a preceding:

```text
PrinterStarted
```

The controller therefore reconciles the current printer state and requests automation startup when the printer is already in `PREPARING` or `PRINTING`.

Startup actions are deduplicated so multiple Bambu events do not repeatedly trigger Wake-on-LAN or future OBS actions.

### Pause

A printer pause does **not** terminate the automation.

A pause may be caused by:

- a manual pause
- filament runout
- a printer warning
- another recoverable print condition

The future livestream therefore remains active while the printer is paused.

### Terminal states

The automation considers these states terminal:

```text
FINISHED
FAILED
```

An initial `FINISHED` snapshot received when the application starts is not treated as a newly finished print.

## Logging

The application uses:

- SLF4J
- Logback

Console output is colorized by log level.

Typical log categories include:

```text
Application
Bambu
Automation
Simulator
WakeOnLan
```

Verbose Bambu events are logged at `DEBUG` level, while automation decisions use `INFO`, `WARN`, or `ERROR`.

## Running

Build:

```powershell
.\gradlew build
```

Run:

```powershell
.\gradlew run
```

On Linux / Raspberry Pi:

```bash
./gradlew build
./gradlew run
```

## Project structure

```text
src/main/kotlin/com/nachidel/bambu/live/
│
├── Main.kt
│
├── automation/
│   └── PrintAutomationController.kt
│
├── bambu/
│   └── BambuPrinterService.kt
│
├── simulator/
│   └── BambuEventSimulator.kt
│
└── studio/
    └── WakeOnLanService.kt
```

## Security

Never commit:

- `BAMBU_TOKEN`
- GitHub personal access tokens
- OBS passwords
- device access codes
- `.env` files containing secrets
- local IntelliJ run configurations containing secrets

Recommended `.gitignore` entries:

```gitignore
.idea/
*.iml

.gradle/
build/
out/

.env
.env.*
local.properties

.DS_Store
Thumbs.db
```

## Related project

This project consumes:

[`nachidel/bambu-cloud-kotlin`](https://github.com/nachidel/bambu-cloud-kotlin)

`bambu-cloud-kotlin` provides the Bambu Cloud connection, MQTT communication, printer snapshots and lifecycle events used by this automation service.

## Disclaimer

Bambu Lab does not currently provide a documented public API for all functionality used by `bambu-cloud-kotlin`.

The underlying SDK relies on observed and reverse-engineered cloud behaviour and may stop working if Bambu Lab changes its services.

Use this project at your own risk.
