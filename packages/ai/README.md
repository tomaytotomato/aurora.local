# ai

Local LLM inference: **Ollama** (model runtime) + **Open-WebUI** (chat
frontend).

## First-run

1. Copy `.env.example` to `.env`. Set `WEBUI_SECRET_KEY` (generate with
   `openssl rand -hex 32`).
2. `./scripts/up.sh core ai`
3. Open-WebUI: `https://ai.$HOME_DOMAIN/` — first user to sign up
   becomes the admin.
4. Pull a model:
   ```
   docker exec -it ollama-cpu ollama pull llama3.2
   docker exec -it ollama-cpu ollama pull qwen2.5-coder:7b
   ```
   (For GPU: replace `ollama-cpu` with `ollama-gpu`.)

## RAM / VRAM guidance

Quantised (Q4) models, rough rule of thumb:

| Model size | Fits in            | Typical use                 |
|------------|--------------------|-----------------------------|
| 1B–3B      | ~4 GB              | quick chat, embeddings      |
| 7B–8B      | ~8 GB              | coding assist (qwen-coder)  |
| 13B        | ~16 GB             | general chat, higher quality|
| 32B–34B    | ~24 GB             | high quality reasoning      |
| 70B        | ~48 GB (GPU only)  | best quality, needs 2× 3090 |

CPU inference is fine for 1B–8B on a modern desktop; expect 5–20 tok/s.
GPU is 5–20× faster.

## GPU passthrough (opt-in)

The `gpu` compose profile brings up an `ollama-gpu` container that
reserves all NVIDIA GPUs on the host.

**Prerequisites (host):**
- NVIDIA driver installed
- NVIDIA Container Toolkit installed and Docker restarted:
  `sudo apt install nvidia-container-toolkit && sudo systemctl restart docker`

**Enabling:**
```
docker stop ollama-cpu   # only one ollama can bind :11434
./scripts/up.sh --gpu core ai
```

Both variants share the `../../data/ai/ollama` volume so pulled models
carry over. Both register `ollama` as a network alias, so Open-WebUI's
`OLLAMA_BASE_URL=http://ollama:11434` works unchanged.

**Note:** The `gpu-nvidia` host role referenced in `manifest.yml` does
not yet exist in `host/roles/`. Until it does, install the NVIDIA
Container Toolkit manually.

## Ports

See `manifest.yml`.
