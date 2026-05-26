# OpenHouse Security Notes

OpenHouse executes local installation scripts and can install command-line AI tools inside Ubuntu. Treat it as a powerful local maintenance tool.

## Reporting

For OpenHouse-specific issues, open a GitHub issue in this fork. For upstream Termux vulnerabilities, follow the upstream Termux security policy.

## Secrets

Do not put provider keys into the APK, repository, screenshots, or issue reports.

Use local environment variables or provider login flows for:

- OpenAI / Codex
- Anthropic / Claude Code
- OpenRouter or other OpenAI-compatible gateways

## Maintenance Source

The online maintenance source is executable configuration. Users should only use sources they trust. The default OpenHouse source is served from GitHub raw under the `jiwuyou/openhouse-bootstrap` repository.

## APK Signing

Debug builds are for testing. If a public production build is needed, use a dedicated private signing key and publish the certificate fingerprint clearly.

