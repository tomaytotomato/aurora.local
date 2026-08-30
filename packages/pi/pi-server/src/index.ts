/**
 * pi-server — the OpenAI-compatible door into Pi.
 *
 * LibreChat POSTs an OpenAI /v1/chat/completions request; we forward the
 * user's newest turn into a persistent Pi AgentSession and stream Pi's
 * assistant deltas back out as OpenAI-shaped SSE chunks. When Pi emits
 * `agent_end`, we send the terminal `[DONE]` and stop the stream.
 *
 * Design choices worth pinning:
 *
 *  * One Pi AgentSession per LibreChat conversation. Keyed off
 *    `user + conversation_id` when present in the request, else off a
 *    stable fingerprint of the message history (first system/user
 *    turn's content hash). The idempotent-key path means Pi's memory
 *    survives across a page reload in LibreChat.
 *  * We do NOT replay the full LibreChat history into Pi on every
 *    request. Pi maintains its own message state. On the first request
 *    for a conversation we seed the session with the entire history;
 *    on subsequent requests we send only the newest user turn.
 *  * Model selection is Pi's, not LibreChat's. `model` in the request
 *    is treated as an advisory hint; whichever provider Pi is
 *    configured with wins. LibreChat's dropdown reads "Pi" and that
 *    is the only shape it sees.
 *  * No streaming = no streaming. We honour `stream: false` by
 *    accumulating deltas server-side and returning one JSON body.
 *  * Errors from Pi surface as a synthesised OpenAI-shape
 *    `error` object; LibreChat renders that inline. Never leak a raw
 *    Node stack trace to the browser.
 *
 * The plan doc is docs/ASSISTANT_PACKAGE_PLAN.md (Phase E2).
 */
import express from "express";
import type { Request, Response } from "express";
import { randomUUID } from "node:crypto";

import {
  createAgentSession,
  ModelRuntime,
  SessionManager,
  type AgentSession,
  type AgentSessionEvent,
} from "@earendil-works/pi-coding-agent";

import {
  pickNextUserTurn,
  sessionKey,
  type ChatCompletionRequest,
  type ChatMessage,
} from "./request.ts";

// ─── config ─────────────────────────────────────────────────────────

/** Port for the HTTP surface. Bound only inside aurora_net. */
const PORT = Number.parseInt(process.env.PI_SERVER_PORT ?? "8080", 10);

/**
 * Model advertised to LibreChat. Also what the LibreChat dropdown
 * renders. Deliberately opinionated: users never pick a raw model here;
 * they pick "Pi", Pi picks the provider.
 */
const MODEL_NAME = process.env.PI_MODEL_NAME ?? "pi";

/**
 * Provider Pi should use under the hood. Defaults to whatever
 * ModelRuntime.getAvailable() returns first, which reads from Pi's
 * own auth store (~/.pi/agent). Bruce runs on Copilot; the compose
 * mounts his auth in as a bind.
 */
const PREFERRED_PROVIDER = process.env.PI_MODEL_PROVIDER ?? "";
const PREFERRED_MODEL_ID = process.env.PI_MODEL_ID ?? "";

/**
 * Idle timeout for a Pi session. LibreChat conversations that go
 * quiet for this long are disposed; the next request creates a fresh
 * one. Keeps memory bounded without dropping mid-conversation.
 */
const SESSION_IDLE_MS = Number.parseInt(process.env.PI_SESSION_IDLE_MS ?? "3600000", 10);

// ─── types ─────────────────────────────────────────────────────────

/** OpenAI chat message shape. LibreChat sends these verbatim. */
interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  name?: string;
}

/** Incoming chat-completions request body (subset we care about). */
interface ChatCompletionRequest {
  model?: string;
  messages: ChatMessage[];
  stream?: boolean;
  /** Present when LibreChat threads a conversation id through. */
  conversation_id?: string;
  user?: string;
}

/** State we keep per LibreChat conversation. */
interface SessionSlot {
  session: AgentSession;
  lastTouchedAt: number;
  /**
   * How many `user`-role messages we have already forwarded into Pi.
   * The next request should forward only the newest turns beyond this
   * count. LibreChat resends the whole history every request, so this
   * is how we avoid replaying it.
   */
  forwardedUserTurns: number;
}

// ─── session management ────────────────────────────────────────────

const sessions = new Map<string, SessionSlot>();
let modelRuntime: ModelRuntime | undefined;

/**
 * Get or create the Pi session for a conversation. First hit seeds the
 * session with any system + prior assistant/user history LibreChat has
 * been carrying around; subsequent hits only need the newest user turn.
 */
async function slotFor(req: ChatCompletionRequest): Promise<SessionSlot> {
  const key = sessionKey(req);
  const existing = sessions.get(key);
  if (existing) {
    existing.lastTouchedAt = Date.now();
    return existing;
  }

  const session = await newSession();
  const slot: SessionSlot = {
    session,
    lastTouchedAt: Date.now(),
    forwardedUserTurns: 0,
  };
  sessions.set(key, slot);
  return slot;
}

async function newSession(): Promise<AgentSession> {
  if (!modelRuntime) {
    modelRuntime = await ModelRuntime.create();
  }
  const model = await pickModel(modelRuntime);
  const { session } = await createAgentSession({
    sessionManager: SessionManager.inMemory(),
    modelRuntime,
    ...(model ? { model } : {}),
  });
  return session;
}

async function pickModel(rt: ModelRuntime) {
  if (PREFERRED_PROVIDER && PREFERRED_MODEL_ID) {
    const explicit = rt.getModel(PREFERRED_PROVIDER, PREFERRED_MODEL_ID);
    if (explicit) return explicit;
    console.warn(
      `pi-server: preferred model ${PREFERRED_PROVIDER}/${PREFERRED_MODEL_ID} not available; falling back`,
    );
  }
  const available = await rt.getAvailable();
  if (available.length === 0) {
    throw new Error(
      "pi-server: no models available. Configure a provider (Copilot, OpenAI, Anthropic) via the mounted auth store.",
    );
  }
  if (PREFERRED_PROVIDER) {
    const scoped = available.find((m) => m.provider === PREFERRED_PROVIDER);
    if (scoped) return scoped;
  }
  return available[0];
}

/**
 * Reap sessions that have been idle for longer than the ceiling. Runs
 * every minute; disposal is a hard release of Pi's own resources.
 */
setInterval(() => {
  const cutoff = Date.now() - SESSION_IDLE_MS;
  for (const [key, slot] of sessions) {
    if (slot.lastTouchedAt < cutoff) {
      try {
        slot.session.dispose();
      } catch {
        // ignore
      }
      sessions.delete(key);
    }
  }
}, 60_000).unref();

// ─── HTTP surface ──────────────────────────────────────────────────

const app = express();
app.use(express.json({ limit: "4mb" }));

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    sessions: sessions.size,
    modelReady: modelRuntime !== undefined,
    model: MODEL_NAME,
  });
});

/**
 * OpenAI /v1/models — LibreChat probes this at startup to populate its
 * dropdown. We advertise exactly one entry, "pi".
 */
app.get("/v1/models", (_req, res) => {
  res.json({
    object: "list",
    data: [
      {
        id: MODEL_NAME,
        object: "model",
        owned_by: "aurora",
        created: Math.floor(Date.now() / 1000),
      },
    ],
  });
});

app.post("/v1/chat/completions", async (req: Request, res: Response) => {
  const body = req.body as ChatCompletionRequest;
  if (!body || !Array.isArray(body.messages) || body.messages.length === 0) {
    return respondError(res, 400, "missing_messages", "request body must include a non-empty `messages` array");
  }

  const stream = body.stream !== false; // default true — LibreChat opts in
  const completionId = `chatcmpl-${randomUUID()}`;
  const created = Math.floor(Date.now() / 1000);

  let slot: SessionSlot;
  try {
    slot = await slotFor(body);
  } catch (err) {
    return respondError(res, 500, "session_init_failed", stringifyError(err));
  }

  const nextUserTurn = pickNextUserTurn(body.messages, slot.forwardedUserTurns);
  if (!nextUserTurn) {
    return respondError(res, 400, "no_user_turn", "no new user turn in the request; last message must be from the user");
  }

  if (stream) {
    return streamCompletion(res, slot, nextUserTurn, completionId, created);
  }
  return jsonCompletion(res, slot, nextUserTurn, completionId, created);
});

// ─── streaming ─────────────────────────────────────────────────────

async function streamCompletion(
  res: Response,
  slot: SessionSlot,
  prompt: string,
  completionId: string,
  created: number,
): Promise<void> {
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  // Belt-and-braces: some proxies buffer without this.
  res.setHeader("X-Accel-Buffering", "no");
  res.flushHeaders();

  const emit = (chunk: Record<string, unknown>) => {
    res.write(`data: ${JSON.stringify(chunk)}\n\n`);
  };

  // Role-only opening chunk, matches OpenAI's own shape.
  emit({
    id: completionId,
    object: "chat.completion.chunk",
    created,
    model: MODEL_NAME,
    choices: [{ index: 0, delta: { role: "assistant" }, finish_reason: null }],
  });

  const done = new Promise<void>((resolve) => {
    const unsubscribe = slot.session.subscribe((event: AgentSessionEvent) => {
      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        emit({
          id: completionId,
          object: "chat.completion.chunk",
          created,
          model: MODEL_NAME,
          choices: [{ index: 0, delta: { content: event.assistantMessageEvent.delta }, finish_reason: null }],
        });
      } else if (event.type === "agent_end" && !event.willRetry) {
        emit({
          id: completionId,
          object: "chat.completion.chunk",
          created,
          model: MODEL_NAME,
          choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
        });
        res.write("data: [DONE]\n\n");
        res.end();
        unsubscribe();
        resolve();
      }
    });
  });

  try {
    slot.forwardedUserTurns += 1;
    await slot.session.prompt(prompt);
    await done;
  } catch (err) {
    // Pi failed mid-turn. Emit a final chunk so LibreChat's UI does
    // not spin forever, then close.
    console.error("pi-server: prompt failed:", err);
    try {
      const msg = `\n\n_(Pi ran into a problem: ${stringifyError(err)})_`;
      res.write(
        `data: ${JSON.stringify({
          id: completionId,
          object: "chat.completion.chunk",
          created,
          model: MODEL_NAME,
          choices: [{ index: 0, delta: { content: msg }, finish_reason: "stop" }],
        })}\n\n`,
      );
      res.write("data: [DONE]\n\n");
      res.end();
    } catch {
      // response already gone
    }
  }
}

// ─── non-streaming ─────────────────────────────────────────────────

async function jsonCompletion(
  res: Response,
  slot: SessionSlot,
  prompt: string,
  completionId: string,
  created: number,
): Promise<void> {
  let assembled = "";
  const done = new Promise<void>((resolve) => {
    const unsubscribe = slot.session.subscribe((event: AgentSessionEvent) => {
      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        assembled += event.assistantMessageEvent.delta;
      } else if (event.type === "agent_end" && !event.willRetry) {
        unsubscribe();
        resolve();
      }
    });
  });

  try {
    slot.forwardedUserTurns += 1;
    await slot.session.prompt(prompt);
    await done;
    res.json({
      id: completionId,
      object: "chat.completion",
      created,
      model: MODEL_NAME,
      choices: [
        {
          index: 0,
          message: { role: "assistant", content: assembled },
          finish_reason: "stop",
        },
      ],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    });
  } catch (err) {
    respondError(res, 500, "pi_prompt_failed", stringifyError(err));
  }
}

// ─── errors ────────────────────────────────────────────────────────

function respondError(res: Response, status: number, code: string, message: string): void {
  if (res.headersSent) return;
  res.status(status).json({
    error: {
      message,
      type: code,
      code,
    },
  });
}

function stringifyError(err: unknown): string {
  if (err instanceof Error) return err.message;
  try {
    return JSON.stringify(err);
  } catch {
    return String(err);
  }
}

// ─── boot ──────────────────────────────────────────────────────────

app.listen(PORT, () => {
  console.log(`pi-server listening on :${PORT} (model=${MODEL_NAME})`);
});
