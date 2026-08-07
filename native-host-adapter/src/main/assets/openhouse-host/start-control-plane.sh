#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

for script in \
  "$HOME/.local/share/openhouseai/control-plane/current/start-control-plane-termux-native.sh" \
  "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer/start-control-plane-termux-native.sh" \
  "$HOME/.local/share/openhouseai/update-resources/current/maintainer/start-control-plane-termux-native.sh"; do
  if [ -r "$script" ]; then
    exec "$PREFIX/bin/bash" "$script"
  fi
done

latest="$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 3 -maxdepth 3 \
  -path '*/maintainer/start-control-plane-termux-native.sh' -type f 2>/dev/null \
  | sort | tail -n 1 || true)"
if [ -n "$latest" ]; then
  exec "$PREFIX/bin/bash" "$latest"
fi

printf 'OpenHouse control-plane starter is not staged in Termux. Run first setup from the Termux host.\n' >&2
exit 2
