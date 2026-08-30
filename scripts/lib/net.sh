#!/usr/bin/env bash
# aurora.local / scripts/lib/net.sh
#
# Working out which address on this box is "the LAN".
#
# This used to be one line — `ip route get 1.1.1.1` — and it was wrong on
# any box with a VPN up, which is a completely normal state for a homelab
# (aurora itself ships Gluetun, and plenty of owners run ProtonVPN,
# WireGuard or Tailscale on the host). The default route then belongs to the
# tunnel, so the "LAN" address came back as the tunnel's own address and the
# "LAN" CIDR as the tunnel's subnet:
#
#   lan_ip=10.2.0.2 lan_cidr=100.85.0.0/24     # a real, observed detection
#
# Everything downstream trusts those two values. group_vars/all.yml feeds
# firewall_lan_cidr, so ufw opens the tunnel subnet and leaves the actual
# LAN — the one the owner's laptop is on — firewalled off; and LAN_IP is
# what AdGuard binds :53 to. A wrong answer here bricks the box for its
# owner on the very first install.
#
# So: pick an interface, not a route. Rules, in order:
#
#   1. Skip interfaces that cannot be a LAN by name: loopback, docker/bridge
#      plumbing, and every VPN/tunnel family (tun, tap, wg, proton, pvpn,
#      tailscale, zt, ppp, vmnet, utun).
#   2. Skip addresses that cannot be a LAN by value: keep RFC1918 only, so
#      CGNAT ranges (100.64/10 — Tailscale, ProtonVPN's kill-switch
#      interface) are out even if someone renames the device.
#   3. Skip prefixes longer than /30: a real LAN is a subnet, not a single
#      host address. This is what rules out ProtonVPN's 10.2.0.2/32, which
#      passes the RFC1918 test.
#   4. Prefer a survivor that owns a default route (lowest metric wins);
#      otherwise take the first survivor. Ethernet before wireless when
#      both are candidates and neither has a default route.
#
# Every function reads its input from a variable so the whole thing is
# testable without a network: set AURORA_IP_ADDR / AURORA_IP_ROUTE to
# captured `ip -4 -o addr show` / `ip -4 route` output.
# See scripts/tests/net.test.sh.

# Captured `ip -4 -o addr show scope global` output (or the live command).
_net_addr_lines() {
  if [[ -n "${AURORA_IP_ADDR:-}" ]]; then
    printf '%s\n' "$AURORA_IP_ADDR"
  else
    ip -4 -o addr show scope global 2>/dev/null
  fi
}

# Captured `ip -4 route` output (or the live command).
_net_route_lines() {
  if [[ -n "${AURORA_IP_ROUTE:-}" ]]; then
    printf '%s\n' "$AURORA_IP_ROUTE"
  else
    ip -4 route 2>/dev/null
  fi
}

# Interface names that can never be the LAN.
_net_iface_excluded() {
  local ifc="$1"
  case "$ifc" in
    lo|lo:*)                                  return 0 ;;
    docker*|br-*|veth*|virbr*|cni*|flannel*|kube*) return 0 ;;
    tun*|tap*|utun*|wg*|wireguard*)           return 0 ;;
    proton*|pvpn*|nordlynx*|mullvad*)         return 0 ;;
    tailscale*|ts[0-9]*|zt*)                  return 0 ;;
    ppp*|vmnet*|vboxnet*)                     return 0 ;;
  esac
  return 1
}

# RFC1918 only. Deliberately excludes 100.64/10 (CGNAT), which is where
# Tailscale and ProtonVPN's kill-switch interface live.
_net_is_private() {
  local ip="$1"
  case "$ip" in
    10.*)         return 0 ;;
    192.168.*)    return 0 ;;
    172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) return 0 ;;
  esac
  return 1
}

# Emits "iface ip/prefix" for every candidate that survives the filters,
# default-route owners first.
_net_candidates() {
  local -a plain=() routed=()
  local line ifc cidr ip prefix

  # Interfaces that own a default route, best (lowest) metric first.
  local -a default_ifaces=()
  while IFS= read -r line; do
    [[ "$line" == default* ]] || continue
    # "default via 192.168.0.1 dev enp0s31f6 proto dhcp src ... metric 100"
    local dev metric
    dev=$(awk '{for(i=1;i<=NF;i++) if($i=="dev") {print $(i+1); exit}}' <<<"$line")
    metric=$(awk '{for(i=1;i<=NF;i++) if($i=="metric") {print $(i+1); exit}}' <<<"$line")
    [[ -n "$dev" ]] || continue
    default_ifaces+=("${metric:-0} $dev")
  done < <(_net_route_lines)
  mapfile -t default_ifaces < <(printf '%s\n' "${default_ifaces[@]:-}" | sort -n)

  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    # "2: enp0s31f6    inet 192.168.0.110/24 brd ... scope global ..."
    ifc=$(awk '{print $2}' <<<"$line")
    cidr=$(awk '{for(i=1;i<=NF;i++) if($i=="inet") {print $(i+1); exit}}' <<<"$line")
    [[ -n "$ifc" && -n "$cidr" ]] || continue
    ifc="${ifc%:}"
    _net_iface_excluded "$ifc" && continue
    ip="${cidr%%/*}"
    prefix="${cidr##*/}"
    _net_is_private "$ip" || continue
    # A /31 or /32 is a point-to-point tunnel address, not a LAN.
    [[ "$prefix" =~ ^[0-9]+$ ]] && (( prefix > 30 )) && continue

    local is_default=0 d
    for d in "${default_ifaces[@]:-}"; do
      [[ "${d#* }" == "$ifc" ]] && is_default=1 && break
    done
    if [[ $is_default -eq 1 ]]; then
      routed+=("$ifc $cidr")
    else
      plain+=("$ifc $cidr")
    fi
  done < <(_net_addr_lines)

  printf '%s\n' "${routed[@]:-}" "${plain[@]:-}" | grep -v '^$' || true
}

# The address other devices on the LAN should use to reach this box.
net_detect_lan_ip() {
  local first; first=$(_net_candidates | head -1)
  [[ -n "$first" ]] || return 0
  local cidr="${first#* }"
  printf '%s\n' "${cidr%%/*}"
}

# The subnet ufw should trust, e.g. 192.168.0.0/24. Derived from the same
# interface net_detect_lan_ip picked, so the two can never disagree.
net_detect_lan_cidr() {
  local first; first=$(_net_candidates | head -1)
  [[ -n "$first" ]] || return 0
  local cidr="${first#* }"
  local ip="${cidr%%/*}" prefix="${cidr##*/}"
  [[ "$prefix" =~ ^[0-9]+$ ]] || return 0

  # Mask the host bits off so we print a network, not an address.
  local IFS=.
  # shellcheck disable=SC2206
  local -a o=($ip)
  local i mask_bits=$prefix
  for i in 0 1 2 3; do
    local bits=$(( mask_bits > 8 ? 8 : (mask_bits < 0 ? 0 : mask_bits) ))
    local mask=$(( 256 - 2 ** (8 - bits) ))
    (( bits == 0 )) && mask=0
    o[i]=$(( o[i] & mask ))
    mask_bits=$(( mask_bits - 8 ))
  done
  printf '%s.%s.%s.%s/%s\n' "${o[0]}" "${o[1]}" "${o[2]}" "${o[3]}" "$prefix"
}

# The interface name itself — avahi needs it so mDNS is not published on
# docker bridges (see the LAN aliases story in the worksheet).
net_detect_lan_iface() {
  local first; first=$(_net_candidates | head -1)
  [[ -n "$first" ]] || return 0
  printf '%s\n' "${first%% *}"
}
