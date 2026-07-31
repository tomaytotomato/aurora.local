# storage

LAN file sharing over SMB (Samba) and DLNA (MiniDLNA).

The media root (`$MEDIA_ROOT` from `group_vars/all.yml`) is exported
read-only by default.

## First-run

1. `./scripts/up.sh storage`
2. Windows: `\\<hostname>.local\media`
3. macOS Finder: **Go → Connect to Server → `smb://<hostname>.local`**
4. DLNA appears automatically on smart TVs/consoles.
