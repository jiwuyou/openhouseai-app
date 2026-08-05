# APK Release Convergence Checklist

This checklist records the WuxianPi desktop registration fix that is currently
released through the `wuxianpi.first-install` market plugin. The current fix is
market-only and does not require rebuilding or resigning an APK. Complete these
items before the next APK release so offline and market-assisted installation
use the same contract.

## WuxianPi component identity

- [ ] `COMPONENT_ID` and `SERVICE_ID` are both `yuanshengwuxianpi`.
- [ ] The embedded manifest is named `components.d/yuanshengwuxianpi.json`.
- [ ] Registry writes use `/api/v1/registry/components/yuanshengwuxianpi`.
- [ ] No new install path generates or references `pi-agent` as the canonical ID.
- [ ] The All-in-One and Native hosts use the same registration script and ID.

## Upgrade compatibility

- [ ] A known WuxianPi `pi-agent.json` is backed up before migration.
- [ ] Unknown or user-defined `pi-agent.json` files are left untouched.
- [ ] Failed cleanup of the old registry entry does not block canonical registration.
- [ ] Re-running registration is idempotent and does not duplicate components.
- [ ] `SMALLPHONEAI_SKIP_OPENHOUSE_SYSTEM=1` does not skip desktop component registration.

## Verification

- [ ] All-in-One first install creates `components.d/yuanshengwuxianpi.json`.
- [ ] Native first install creates `components.d/yuanshengwuxianpi.json`.
- [ ] Registry API returns `yuanshengwuxianpi` after registration and sync.
- [ ] Restarting the native OpenHouse app displays the WuxianPi desktop entry.
- [ ] The offline embedded fallback follows the market plugin 1.0.3 behavior.
- [ ] Existing installations can update the plugin and rerun the registration step
      without reinstalling WuxianPi or rebuilding the APK.

## Release gate

- [ ] Run the All-in-One and Native first-install checks on a clean device.
- [ ] Confirm the component remains visible when service-manager is stopped on
      demand and becomes usable after WuxianPi is started.
- [ ] Keep the market plugin's historical releases `1.0.0` through `1.0.3`
      available for rollback and compatibility testing.
