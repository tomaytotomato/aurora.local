# home-automation

Home Assistant + Mosquitto (MQTT) + Zigbee2MQTT.

## Host networking caveat

Home Assistant runs with `network_mode: host`. This is HA's own
recommendation: local-discovery integrations (Chromecast, HomeKit,
mDNS, SSDP) do not survive a docker bridge network. Consequence:

- HA is **not** attached to `aurora_net`.
- Caddy in `core` reaches it via `host.docker.internal:8123` (Linux
  has `extra_hosts: host-gateway` on the caddy service).
- HA reaches Mosquitto over the host's LAN IP or 127.0.0.1:1883 (not
  by container name).

Mosquitto and Zigbee2MQTT stay on `aurora_net` and are reachable by
name from other packages.

## First-run

1. `./scripts/up.sh core home-automation`
2. Complete HA onboarding at `http://<lan-ip>:8123`.
3. In HA: Settings → Devices & Services → Add MQTT integration.
   Point at `<lan-ip>:1883`.

## Mosquitto credentials

The broker starts anonymous by default (dev-friendly). Before
exposing it, create a user and lock it down:

    docker exec -it mosquitto \
        mosquitto_passwd -c /mosquitto/config/passwd myuser

Then edit `data/home-automation/mosquitto/config/mosquitto.conf`:

    allow_anonymous false
    password_file /mosquitto/config/passwd
    listener 1883
    listener 8883
    # ...cafile/certfile/keyfile for TLS if desired

Restart mosquitto: `docker restart mosquitto`.

## Zigbee2MQTT (profile: `zigbee`)

Zigbee2MQTT is opt-in — enable only if you have a Zigbee coordinator.

1. Plug in the USB coordinator (Sonoff ZBDongle-P/E, ConBee II,
   CC2531, etc.).
2. Find its stable path:
       ls -l /dev/serial/by-id/
3. Set `ZIGBEE_ADAPTER` in `.env` to that path.
4. Create `data/home-automation/zigbee2mqtt/configuration.yaml` with
   the minimum:

        homeassistant: true
        permit_join: false
        mqtt:
          base_topic: zigbee2mqtt
          server: mqtt://mosquitto:1883
        serial:
          port: /dev/ttyACM0
        frontend:
          port: 8080

5. Bring up with the profile:

        COMPOSE_PROFILES=zigbee ./scripts/up.sh core home-automation
        # or, if scripts/up.sh gains a --zigbee flag:
        ./scripts/up.sh --zigbee core home-automation

6. Web UI: `http://<lan-ip>:8084` (remapped from 8080 to avoid
   colliding with gluetun's qBittorrent publish).

## Ports

See `manifest.yml`.
