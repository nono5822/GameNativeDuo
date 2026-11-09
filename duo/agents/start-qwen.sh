#!/usr/bin/env bash
# Start the local Qwen worker for GameNative Duo agent runs.
#
# Your friend's tested config, with the changes from our discussion:
#   - KV cache q8_0 instead of q4_0 (better accuracy on code and logs)
#   - 24k context by default (fits every duo source file plus its test; logs are pre-filtered by triage.py)
#   - bound to 127.0.0.1 only: nothing else on your network needs this server
#
# Override anything with environment variables, e.g.
#   QWEN_MODEL=unsloth/Qwen3.8-27B-GGUF:UD-IQ3_M QWEN_CTX=20480 ./start-qwen.sh
#
# If the server crashes with an out-of-memory error, lower QWEN_CTX first, then try
# QWEN_KV=q4_0 (the original config). Leave ~1 GB of VRAM free if your monitor is on the 9070 XT.

set -euo pipefail

LLAMA_BIN="${LLAMA_BIN:-llama}"                       # your friend's `llama serve` CLI
QWEN_MODEL="${QWEN_MODEL:-unsloth/Qwen3.8-27B-GGUF:UD-IQ3_XXS}"
QWEN_CTX="${QWEN_CTX:-24576}"
QWEN_PORT="${QWEN_PORT:-9931}"
QWEN_KV="${QWEN_KV:-q8_0}"

exec "$LLAMA_BIN" serve -hf "$QWEN_MODEL" \
  --ctx-size "$QWEN_CTX" \
  --port "$QWEN_PORT" \
  --host 127.0.0.1 \
  --n-gpu-layers all \
  --gpu-layers-draft all \
  --spec-type draft-mtp \
  --spec-draft-n-max 3 \
  --flash-attn on \
  --parallel 1 \
  --cache-type-k "$QWEN_KV" \
  --cache-type-v "$QWEN_KV" \
  --batch-size 512 \
  --ubatch-size 512 \
  --fit off \
  --no-mmproj
