# Architecture

Four mermaid diagrams: layered stack, bootstrap sequence, request flow,
and the control plane — which is the one to read first if you are asking
"what actually drives what".

Render on GitHub by opening this file, or paste any block into
<https://mermaid.live> to iterate.

## 1. Layered view

```mermaid
graph TB
    classDef hardware fill:#334155,stroke:#94a3b8,color:#fff
    classDef os fill:#1e40af,stroke:#60a5fa,color:#fff
    classDef ansible fill:#7c2d12,stroke:#f97316,color:#fff
    classDef docker fill:#0e7490,stroke:#22d3ee,color:#fff
    classDef control fill:#166534,stroke:#4ade80,color:#fff
    classDef pkg fill:#581c87,stroke:#c084fc,color:#fff
    classDef edge fill:#9f1239,stroke:#fb7185,color:#fff
    classDef client fill:#713f12,stroke:#facc15,color:#000

    subgraph L7["L7 — Client devices"]
        Phone[📱 Phone]
        Laptop[💻 Laptop]
        TV[📺 TV / console]
    end

    subgraph L6["L6 — Edge (DNS + TLS + auth + dashboard)"]
        AdGuard["🛡️ AdGuard Home<br/>LAN DNS + *.aurora.local rewrites"]
        Caddy["🔐 Caddy<br/>:80/:443 + internal CA"]
        Authelia["🔑 Authelia<br/>forward-auth SSO/2FA"]
        Aurora["📊 Aurora<br/>dashboard, served at the apex vhost"]
    end

    subgraph L5["L5 — Packages (docker-compose bundles)"]
        direction LR
        subgraph Core["core"]
            C1[caddy]
        end
        subgraph Privacy["privacy"]
            P1[adguard]
            P2[gluetun]
        end
        subgraph Media["media"]
            M1[sonarr/radarr]
            M2[prowlarr/bazarr]
            M3[jellyseerr]
            M4[qbittorrent]
        end
        subgraph Storage["storage"]
            S1[samba]
            S2[minidlna]
        end
        subgraph Monitoring["monitoring"]
            MO1[prometheus]
            MO2[grafana]
            MO3[uptime-kuma]
        end
        subgraph More["+8 others"]
            Photos[photos<br/>Immich]
            Docs[documents<br/>Paperless]
            Notes[notes<br/>SilverBullet]
            Dev[dev<br/>code-server]
            AI[ai<br/>Ollama]
            HA[home-automation<br/>HomeAssistant]
            ID[identity<br/>Authelia]
            BK[backup<br/>Kopia]
        end
    end

    subgraph L4["L4 — Installer & control plane"]
        BS["bootstrap.sh<br/>install / add / remove / list / status"]
        Scripts["scripts/*.sh<br/>up down status doctor health<br/>backup pin rotate-secrets"]
        Lib["scripts/lib/*<br/>log · prompt · manifest · state · render · ops"]
        State[".state.yml<br/>enabled pkgs + profiles"]
        Manifests["packages/*/manifest.yml<br/>schema-validated metadata"]
    end

    subgraph L3["L3 — Container runtime"]
        DockerEng["🐳 Docker Engine + compose plugin"]
        Net["aurora_net<br/>shared bridge network"]
        Proj["compose project: aurora"]
    end

    subgraph L2["L2 — Host provisioning (Ansible)"]
        direction LR
        R1[common]
        R2[docker]
        R3[firewall<br/>ufw]
        R4[ssh-hardening]
        R5[fail2ban]
        R6[swap-file]
        R7[storage-mount]
        R8[avahi]
        R9[unattended-<br/>upgrades]
        R10[caddy-trust<br/>opt-in]
    end

    subgraph L1["L1 — *nix (Debian 12 / Ubuntu 24.04)"]
        Kernel[kernel + systemd]
        Apt[apt]
        FS["ext4/btrfs + fstab"]
        MDNS[avahi-daemon]
    end

    subgraph L0["L0 — Hardware"]
        HW[Optiplex / NUC / VM]
    end

    L7 -.->|LAN DNS| AdGuard
    L7 -.->|HTTPS| Caddy
    AdGuard -->|answers *.aurora.local| Caddy
    Caddy -->|forward_auth| Authelia
    Caddy -->|reverse_proxy| L5
    Caddy -->|apex vhost| Aurora

    L5 --> Proj
    Proj --> DockerEng
    Net --> Proj

    BS --> Scripts
    Scripts --> Lib
    Lib --> Manifests
    Lib --> State
    State --> Scripts
    Scripts -->|docker compose up -d| Proj

    L2 -->|installs| DockerEng
    L2 -->|installs| Apt
    L2 -->|configures| Kernel

    L1 --> L0

    class L0 hardware
    class L1 os
    class L2 ansible
    class L3 docker
    class L4 control
    class L5 pkg
    class L6 edge
    class L7 client
```

## 2. Bootstrap sequence

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Curl as curl / TTY
    participant BS as bootstrap.sh
    participant Ansible as host/site.yml
    participant State as .state.yml
    participant Render as scripts/lib/render.sh
    participant Compose as docker compose

    User->>Curl: curl -fsSL .../bootstrap.sh | bash
    Curl->>BS: exec
    BS->>BS: install prereqs<br/>(git, ansible, yq, whiptail)
    BS->>BS: clone repo, re-exec

    alt interactive TTY
        BS->>User: whiptail: hostname / domain / TZ / user
        BS->>User: whiptail checklist: pick packages
    else headless
        BS->>BS: read ENABLE_PACKAGES + env
    end

    BS->>State: write hostname, domain, enabled[], installed_at

    BS->>Ansible: ansible-playbook host/site.yml -K --connection=local
    Ansible->>Ansible: common / docker / firewall / ssh / fail2ban
    Ansible->>Ansible: swap-file / storage-mount / avahi / uu
    Ansible-->>BS: host ready

    BS->>Compose: scripts/up.sh (with resolved deps)
    Compose->>Compose: create aurora_net if missing
    Compose->>Compose: seed .env from .env.example (first run)
    Compose->>Compose: source every packages/*/.env into shell
    Compose->>Compose: getent group docker → DOCKER_GID
    Compose->>Render: render_all(enabled_pkgs)
    Render->>Render: copy caddy.snippet → data/caddy/snippets/
    Render->>Render: seed users_database.yml (if identity)
    Render->>Render: source pins.env (if present)
    Compose->>Compose: docker compose -p aurora -f pkg1 -f pkg2 ... up -d

    Compose-->>User: containers running
    Note over User,Compose: http(s)://<domain> reachable — Aurora serves the apex vhost
```

## 3. Request flow (client hitting a service)

```mermaid
graph LR
    classDef client fill:#713f12,stroke:#facc15,color:#000
    classDef edge fill:#9f1239,stroke:#fb7185,color:#fff
    classDef app fill:#581c87,stroke:#c084fc,color:#fff
    classDef backend fill:#0e7490,stroke:#22d3ee,color:#fff

    Phone["📱 sonarr.aurora.local"]

    subgraph LAN
        Router[Router DHCP<br/>DNS = home box]
    end

    subgraph HomeBox["home box (aurora / whatever)"]
        AG["AdGuard :53<br/>rewrite → LAN_IP"]
        Cad["Caddy :443<br/>tls internal"]
        Aut["Authelia :9091<br/>forward_auth"]
        Son["sonarr:8989"]
        Prow["prowlarr:9696"]
        Rdt["rdtclient:6500"]
    end

    Phone -->|"1 DNS query"| Router
    Router -->|"2 forward"| AG
    AG -->|"3 answer LAN_IP"| Phone
    Phone -->|"4 HTTPS"| Cad
    Cad -->|"5 (if import authelia)"| Aut
    Aut -->|"6 allowed + headers"| Cad
    Cad -->|"7 reverse_proxy on aurora_net"| Son
    Son -.->|indexer| Prow
    Son -.->|download client| Rdt

    class Phone client
    class AG,Cad,Aut edge
    class Son app
    class Prow,Rdt backend
```

## 4. Control plane — what drives what

The layered view above shows where things sit. This shows how the box gets
built and changed, and it is the diagram people get wrong: the two halves
(Ansible for the host, bash + compose for the apps) are not siblings —
`bootstrap.sh` drives both, in order — and the dashboard is not above the
apps, it *is* one of them.

```mermaid
graph TD
    classDef operator fill:#713f12,stroke:#facc15,color:#000
    classDef truth fill:#334155,stroke:#94a3b8,color:#fff
    classDef ansible fill:#7c2d12,stroke:#f97316,color:#fff
    classDef script fill:#166534,stroke:#4ade80,color:#fff
    classDef host fill:#1e40af,stroke:#60a5fa,color:#fff
    classDef pkg fill:#581c87,stroke:#c084fc,color:#fff

    Human["Operator at a TTY"] -->|"curl to bash"| BS["bootstrap.sh<br/>the only entry point"]

    BS -->|"writes, then reads"| Files["Source of truth: plain files<br/>group_vars/all.yml — host answers<br/>.state.yml — enabled packages<br/>packages/*/manifest.yml — deps, ports, vhosts, sso, backup"]

    BS -->|"step 1: the host"| Site["host/site.yml<br/>Ansible, root, runs once"]
    Site --> Roles["15 roles: docker, firewall, ssh-hardening,<br/>fail2ban, dns-stub, avahi, swap, smartd"]
    Roles --> Host["Debian-family host<br/>apt, ufw, systemd units, port 53 freed"]

    BS -->|"step 2: the apps"| Up["scripts/up.sh<br/>CONVERGES the box to the set it is given"]
    Files -->|"deps resolved, enabled[] read"| Up
    Up -->|"rewrites enabled[]"| Files

    Up --> Render["lib/render.sh<br/>caddy snippets, identity seed, image pins"]
    Up --> Compose["docker compose -p aurora<br/>-f every enabled package"]
    Down["scripts/down.sh"] --> Compose
    Scoped["scripts/restart.sh<br/>scripts/upgrade.sh<br/>one package, converge nothing"] --> Compose

    Compose --> Aurora["dashboard: the Aurora app<br/>Spring Boot + Vue"]
    Compose --> Rest["the other 16 packages<br/>core, privacy, identity, media, photos,<br/>documents, monitoring, storage, backup"]

    Aurora ==>|"THE LOOP: the dashboard is itself an app,<br/>and manages the others by shelling back out<br/>behind a self-recreation guard"| Up
    Aurora ==> Scoped

    class Human operator
    class Files truth
    class BS,Site,Roles ansible
    class Up,Down,Scoped,Render,Compose script
    class Host host
    class Aurora,Rest pkg
```

Three properties worth stating in words, because they are the ones that
cause bugs:

**`up.sh` converges; it does not "start".** It ends with
`state_set_enabled "${pkgs[@]}"` and passes `--remove-orphans`, so
`up.sh media` means "make this box be core+media" — it rewrites
`.state.yml` and reaps every other package's containers. `restart.sh` and
`upgrade.sh` exist precisely so that acting on one package cannot do that.

**The loop is real and it bites.** The dashboard runs in a container that
`up.sh` manages, and it manages packages by running `up.sh`. Recreating
the container that is running the script kills the script mid-invocation;
that was a genuine bug, and both scripts now carry a guard keyed on
`AURORA_LAUNCHED_BY` / `AURORA_INVOKED_BY`.

**There is no database in the control plane.** Every input is a file in
the repo — manifests, `.state.yml`, `group_vars/all.yml`. The dashboard's
SQLite holds its own concerns (users, audit, settings, VPN peers), not
what the box is.

## 5. How the backend talks to a packaged service

**Standing decision: drive a packaged service through its CLI, not its
HTTP API.** Where a service ships both — Kopia, AdGuard, Authelia,
qBittorrent, Immich — the CLI is the interface Aurora uses, invoked in the
service's own container:

```
docker exec <container> <tool> <subcommand> --json
```

The dashboard backend already drives every external tool this way through
`CommandRunner`: `wg` (VpnService), `smartctl` and `snapraid`
(DisksService), `kopia` (BackupService), and `up.sh`/`down.sh`
(PackageLifecycleService, LaunchService). One seam, one place to fake in
tests (`FakeCommandRunner`), one place where timeouts, cancellation and
line streaming are already solved.

### Why not the HTTP API

- **Credentials.** A service's HTTP API needs its admin password, which
  lives in *that package's* `.env`. Reaching across package boundaries to
  read another package's secrets is a coupling worth refusing; the CLI
  inside the container is already authenticated by being there.
- **One failure vocabulary.** A CLI gives an exit code, stdout and stderr.
  Every consumer here already knows how to classify that
  (`JobFailureClassifier`), and it is the same story whether the tool is
  `wg` or `kopia`.
- **Testability.** `FakeCommandRunner` stubs a command by substring. An
  HTTP client needs a WireMock per service, and a second set of auth
  fixtures.
- **Version drift.** These projects change their HTTP APIs more freely
  than their CLI flags, and a broken CLI flag fails loudly at the exit
  code rather than silently returning a differently-shaped JSON body.

### When to break the rule

Reach for HTTP when the CLI genuinely cannot answer: streaming or
push-style data (progress events, live logs), an operation with no CLI
equivalent at all, or a service that ships no CLI. Say so in the service's
class javadoc when you do, and name what the CLI could not do — that
sentence is the point of this section.

### What this does not cover

Talking to *Docker* is `DockerService` (docker-java), not a shelled-out
`docker` CLI, because it is a long-lived API this backend depends on
structurally rather than a packaged app. Reading a file a host role wrote
(`DisksService` and the disk-state JSON) is a file read, not a CLI call.
