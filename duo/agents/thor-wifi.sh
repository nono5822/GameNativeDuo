#!/usr/bin/env bash
# adb to the AYN Thor over Wi-Fi instead of USB.
#
#   duo/agents/thor-wifi.sh                 connect (default); prints the wireless serial, e.g. 192.168.2.199:5555
#   duo/agents/thor-wifi.sh pair HOST:PORT CODE   pair once with Android's "Wireless debugging" (survives reboots)
#   duo/agents/thor-wifi.sh disconnect
#
# How `connect` finds the Thor, in order:
#   1. Thor on USB: switch adbd to TCP (`adb tcpip`), read its wlan0 address, connect, remember the address.
#      After this the cable can be unplugged. Lasts until the Thor reboots.
#   2. Remembered address (~/.config/gamenative-duo/thor-addr): reconnect to it.
#   3. Wireless debugging over mDNS (needs `pair` once; Developer options > Wireless debugging must be on).
#
# Use it from other scripts with: export ANDROID_SERIAL="$(duo/agents/thor-wifi.sh)"
# Gradle (installModernDebug) and every plain `adb` call then target the Thor over Wi-Fi, even with the cable in.
#
# PC and Thor must be on the same network. Anyone on that network can reach adbd while TCP mode is on;
# `disconnect` turns it back to USB only.

set -euo pipefail

PORT="${THOR_ADB_PORT:-5555}"
STATE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/gamenative-duo"
ADDR_FILE="$STATE_DIR/thor-addr"

log() { echo "$*" >&2; }

# Serials of attached Thors (USB or network) in the "device" state.
thor_serials() { adb devices -l | awk '$2 == "device" && /model:AYN_Thor/ { print $1 }'; }
usb_thor() { thor_serials | grep -v ':' | head -1 || true; }
wifi_thor() { thor_serials | grep ':' | head -1 || true; }

try_connect() {
  local addr="$1"
  adb connect "$addr" 2>&1 | grep -q '^connected to\|already connected' || return 1
  for _ in 1 2 3 4 5; do
    [ "$(adb -s "$addr" get-state 2>/dev/null || true)" = device ] && return 0
    sleep 1
  done
  return 1
}

connect() {
  local serial ip addr
  serial="$(wifi_thor)"
  if [ -n "$serial" ]; then echo "$serial"; return; fi

  # 1. over USB: enable TCP mode
  serial="$(usb_thor)"
  if [ -n "$serial" ]; then
    ip="$(adb -s "$serial" shell ip -4 addr show wlan0 | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }')"
    if [ -z "$ip" ]; then
      log "The Thor is not on Wi-Fi (no wlan0 address). Connect it to the same network as this PC."
      exit 1
    fi
    log "Switching the Thor ($serial) to adb over Wi-Fi at $ip:$PORT..."
    adb -s "$serial" tcpip "$PORT" >/dev/null
    adb -s "$serial" wait-for-device
    sleep 1
    if try_connect "$ip:$PORT"; then
      mkdir -p "$STATE_DIR"
      echo "$ip:$PORT" >"$ADDR_FILE"
      log "Connected. You can unplug the cable."
      echo "$ip:$PORT"
      return
    fi
    log "Could not reach $ip:$PORT (firewall, or client isolation on the Wi-Fi?)."
    exit 1
  fi

  # 2. remembered address
  if [ -f "$ADDR_FILE" ]; then
    addr="$(cat "$ADDR_FILE")"
    if try_connect "$addr"; then echo "$addr"; return; fi
    adb disconnect "$addr" >/dev/null 2>&1 || true
    log "No answer at $addr (the Thor rebooted, changed IP, or is asleep)."
  fi

  # 3. Wireless debugging, found by mDNS
  addr="$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ { print $NF; exit }')"
  if [ -n "$addr" ] && try_connect "$addr"; then
    # mDNS connections can show up under the service name instead of HOST:PORT
    serial="$(wifi_thor)"
    [ -n "$serial" ] || serial="$(thor_serials | head -1)"
    echo "${serial:-$addr}"
    return
  fi

  log "Thor not found. Either plug it in once and rerun this, or turn on Developer options >"
  log "Wireless debugging and run: $0 pair HOST:PORT CODE  (from 'Pair device with pairing code')."
  exit 1
}

case "${1:-connect}" in
  connect) connect ;;
  pair)
    adb pair "${2:?usage: $0 pair HOST:PORT CODE}" "${3:?usage: $0 pair HOST:PORT CODE}"
    sleep 2
    connect
    ;;
  disconnect)
    serial="$(wifi_thor)"
    if [ -n "$serial" ]; then
      adb -s "$serial" usb >/dev/null 2>&1 || true   # back to USB-only adbd
      adb disconnect "$serial" >/dev/null 2>&1 || true
      log "Disconnected $serial; the Thor's adbd is USB-only again."
    fi
    ;;
  *) log "usage: $0 [connect | pair HOST:PORT CODE | disconnect]"; exit 2 ;;
esac
