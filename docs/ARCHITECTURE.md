# Architecture

Three mermaid diagrams: layered stack, bootstrap sequence, request flow.

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
    Render->>Render: services.base.yaml + homepage.yml → services.yaml (vestigial: no Homepage container reads this since v0.1)
    Render->>Render: seed users_database.yml (if identity)
    Render->>Render: source pins.env (if present)
    Compose->>Compose: docker compose -p aurora -f pkg1 -f pkg2 ... up -d

    Compose-->>User: containers running
    Note over User,Compose: http(s)://home.aurora.local reachable
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
