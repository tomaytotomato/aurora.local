/**
 * Pure helpers pulled out of index.ts so they can be unit-tested
 * without spinning up the Pi runtime, express, or the network. Nothing
 * here talks to Pi; that's the whole point of the split.
 */
import { createHash } from "node:crypto";

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  name?: string;
}

export interface ChatCompletionRequest {
  model?: string;
  messages: ChatMessage[];
  stream?: boolean;
  conversation_id?: string;
  user?: string;
}

/**
 * Fingerprint a request to a stable session id.
 *
 * <p>Two conversations from the same user MUST get different keys.
 * Two requests inside the SAME conversation MUST get the same key
 * across a page reload. LibreChat sometimes threads `conversation_id`
 * through; when it does, that wins. Otherwise we hash the first
 * system-or-user message, which LibreChat keeps stable for the life
 * of the conversation.
 */
export function sessionKey(req: ChatCompletionRequest): string {
  if (req.conversation_id) {
    return `conv:${req.user ?? "anon"}:${req.conversation_id}`;
  }
  const seed = req.messages.find((m) => m.role === "system") ?? req.messages[0];
  const h = createHash("sha256")
    .update(seed?.content ?? "")
    .digest("hex")
    .slice(0, 16);
  return `hash:${req.user ?? "anon"}:${h}`;
}

/**
 * Pick the next user turn to forward into Pi. LibreChat resends the
 * entire message history on every request. We keep track of how many
 * user turns we have already forwarded; the newest one after that
 * count is what Pi hasn't seen yet.
 *
 * @returns the message text, or undefined when there is nothing new
 *          (which is a bug in the caller and gets a 400).
 */
export function pickNextUserTurn(
  messages: ChatMessage[],
  forwardedCount: number,
): string | undefined {
  const userTurns = messages
    .filter((m) => m.role === "user")
    .map((m) => m.content);
  if (userTurns.length <= forwardedCount) return undefined;
  return userTurns[userTurns.length - 1];
}
