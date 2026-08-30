#!/usr/bin/env bash
# aurora.local / scripts/tests/net.test.sh
#
# Fixtures for scripts/lib/net.sh. Every case is real `ip` output captured
# from a box where the old one-line detection got it wrong (or would have).
#
#   ./scripts/tests/net.test.sh
set -uo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../lib/net.sh
. "$REPO/scripts/lib/net.sh"

pass=0
fail=0

check() {
  local name="$1" want="$2" got="$3"
  if [[ "$want" == "$got" ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$name" "$want" "$got" >&2
  fi
}

# ---------------------------------------------------------------------
# 1. The box this was found on: ethernet LAN + ProtonVPN (kill-switch
#    interface on CGNAT, tunnel on a /32) + docker bridges. The tunnel owns
#    the best default route, which is exactly what fooled `ip route get`.
# ---------------------------------------------------------------------
export AURORA_IP_ADDR='1: lo    inet 127.0.0.1/8 scope host lo
2: enp0s31f6    inet 192.168.0.110/24 brd 192.168.0.255 scope global dynamic noprefixroute enp0s31f6
4: pvpnksintrf0    inet 100.85.0.1/24 brd 100.85.0.255 scope global noprefixroute pvpnksintrf0
5: proton0    inet 10.2.0.2/32 scope global noprefixroute proton0
6: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0
897: br-e85e705a182c    inet 172.18.0.1/16 brd 172.18.255.255 scope global br-e85e705a182c'
export AURORA_IP_ROUTE='default via 100.85.0.1 dev pvpnksintrf0 proto static metric 98
default via 192.168.0.1 dev enp0s31f6 proto dhcp src 192.168.0.110 metric 100
100.85.0.0/24 dev pvpnksintrf0 proto kernel scope link src 100.85.0.1 metric 98
172.17.0.0/16 dev docker0 proto kernel scope link src 172.17.0.1 linkdown
172.18.0.0/16 dev br-e85e705a182c proto kernel scope link src 172.18.0.1
192.168.0.0/24 dev enp0s31f6 proto kernel scope link src 192.168.0.110 metric 100'

check "vpn box: ip"    "192.168.0.110"   "$(net_detect_lan_ip)"
check "vpn box: cidr"  "192.168.0.0/24"  "$(net_detect_lan_cidr)"
check "vpn box: iface" "enp0s31f6"       "$(net_detect_lan_iface)"

# ---------------------------------------------------------------------
# 2. Plain box, one NIC, no VPN. The easy case must stay easy.
# ---------------------------------------------------------------------
export AURORA_IP_ADDR='1: lo    inet 127.0.0.1/8 scope host lo
2: eth0    inet 192.168.1.42/24 brd 192.168.1.255 scope global dynamic eth0
3: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0'
export AURORA_IP_ROUTE='default via 192.168.1.1 dev eth0 proto dhcp metric 100
172.17.0.0/16 dev docker0 proto kernel scope link src 172.17.0.1
192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.42 metric 100'

check "plain box: ip"   "192.168.1.42"   "$(net_detect_lan_ip)"
check "plain box: cidr" "192.168.1.0/24" "$(net_detect_lan_cidr)"

# ---------------------------------------------------------------------
# 3. Tailscale, which sits on CGNAT and often owns a default route.
# ---------------------------------------------------------------------
export AURORA_IP_ADDR='1: lo    inet 127.0.0.1/8 scope host lo
2: wlp2s0    inet 10.0.0.57/24 brd 10.0.0.255 scope global wlp2s0
4: tailscale0    inet 100.101.102.103/32 scope global tailscale0'
export AURORA_IP_ROUTE='default dev tailscale0 scope link metric 50
default via 10.0.0.1 dev wlp2s0 proto dhcp metric 600
10.0.0.0/24 dev wlp2s0 proto kernel scope link src 10.0.0.57 metric 600'

check "tailscale: ip"   "10.0.0.57"   "$(net_detect_lan_ip)"
check "tailscale: cidr" "10.0.0.0/24" "$(net_detect_lan_cidr)"

# ---------------------------------------------------------------------
# 4. A /22 LAN — the mask arithmetic has to actually mask.
# ---------------------------------------------------------------------
export AURORA_IP_ADDR='2: eno1    inet 172.20.6.31/22 brd 172.20.7.255 scope global eno1'
export AURORA_IP_ROUTE='default via 172.20.4.1 dev eno1 proto static metric 100
172.20.4.0/22 dev eno1 proto kernel scope link src 172.20.6.31'

check "/22 lan: cidr" "172.20.4.0/22" "$(net_detect_lan_cidr)"

# ---------------------------------------------------------------------
# 5. No default route at all (a box on an isolated switch): still find the
#    one plausible LAN interface rather than giving up.
# ---------------------------------------------------------------------
export AURORA_IP_ADDR='1: lo    inet 127.0.0.1/8 scope host lo
2: enp3s0    inet 192.168.50.5/24 brd 192.168.50.255 scope global enp3s0
3: docker0    inet 172.17.0.1/16 scope global docker0'
export AURORA_IP_ROUTE='172.17.0.0/16 dev docker0 proto kernel scope link src 172.17.0.1
192.168.50.0/24 dev enp3s0 proto kernel scope link src 192.168.50.5'

check "no default route: ip" "192.168.50.5" "$(net_detect_lan_ip)"

# ---------------------------------------------------------------------
# 6. Nothing usable: return empty rather than an invented address, so the
#    caller can fall back and say so.
# ---------------------------------------------------------------------
export AURORA_IP_ADDR='1: lo    inet 127.0.0.1/8 scope host lo'
export AURORA_IP_ROUTE=''

check "nothing: ip"   "" "$(net_detect_lan_ip)"
check "nothing: cidr" "" "$(net_detect_lan_cidr)"

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[[ $fail -eq 0 ]]
