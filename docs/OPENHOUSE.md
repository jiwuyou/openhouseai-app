# OpenHouse Overview

OpenHouse adds an installation and maintenance layer on top of Termux. The goal is to make a phone bootstrappable into an Ubuntu-based AI development environment with clear stages, visible logs, and a dynamic maintenance source that can change without rebuilding the APK.

## Main Features

- Maintenance center entry from the Termux main screen.
- Permission section for battery optimization, overlay, and storage access.
- Execution mode section with manual stages and one-click stages.
- One-click stages are rendered as individual steps, so OpenCode, Codex, Claude Code, skills, and service startup are not hidden inside a merged group.
- Dynamic maintenance plugin source:
  - APK bundled manifest.
  - User-editable local manifest.
  - Online GitHub raw manifest.
- Local web maintenance service with configurable port.
- Quick buttons on the main Termux screen:
  - `维护` opens the maintenance center.
  - `OC` launches OpenCode.
  - `显隐` toggles the quick buttons and can be dragged.

## Runtime Model

OpenHouse uses Termux as the Android host and installs the Linux userland through `proot-distro`.

The intended stack is:

1. Termux app starts.
2. Maintenance center prepares Termux packages.
3. Ubuntu is installed through `proot-distro`.
4. OpenCode is installed and started on a local port.
5. Codex and Claude Code are installed inside Ubuntu through the online maintenance scripts.
6. OpenCode skills are written for Agent installation, official login flows, and third-party API configuration examples.

## Online Maintenance Source

The default online source is:

```text
https://raw.githubusercontent.com/jiwuyou/openhouse-bootstrap/main/openhouse-manifest.json
```

The source can change stage titles, descriptions, dynamic buttons, and bootstrap arguments without requiring a new APK build.

The source intentionally does not contain API keys. Users configure their own providers locally.

## Related Repositories

- Bootstrap scripts: <https://github.com/jiwuyou/openhouse-bootstrap>
- Guide site: <https://github.com/jiwuyou/openhouse-app-guide-site>

