#!/bin/sh
# Void Linux + BlueZ setup for the Nokia 6300 link.
# Run: ./scripts/void-bt-setup.sh   (sudo password "0" on this box)
set -eu
PHONE="00:22:66:C8:4C:07"

SUDO="sudo"
if [ "$(id -u)" = "0" ]; then SUDO=""; fi

echo "== bluetoothd (runit) =="
${SUDO} sv status bluetoothd || ${SUDO} sv start bluetoothd || true

echo "== power on + discoverable + pairable (180s window) =="
echo 0 | ${SUDO} -S bluetoothctl power on
echo 0 | ${SUDO} -S bluetoothctl discoverable on
echo 0 | ${SUDO} -S bluetoothctl pairable on
echo 0 | ${SUDO} -S bluetoothctl trust "$PHONE"

echo "== status =="
echo 0 | ${SUDO} -S bluetoothctl show | grep -E "Powered|Discoverable|Pairable|Name" || true
echo 0 | ${SUDO} -S bluetoothctl info "$PHONE" | grep -E "Name|Paired|Trusted|Connected|Serial" || true

cat <<'EOF'

Next on the Nokia 6300:
  Menu -> Settings -> Connectivity -> Bluetooth -> On, Visibility -> Shown to all
  Search for "BlueZ 5.86", pair if asked, connect to service "OpenCode-Nokia".
  (Already bonded, so usually no PIN is needed anymore.)

Then in the MIDlet: Menu -> Connect.
Fallback (emulator/PAN): set JAD property TcpHost=<this PC's IP>, port 19741.
EOF
