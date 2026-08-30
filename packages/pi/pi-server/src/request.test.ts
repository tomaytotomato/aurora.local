/**
 * Unit tests for the pure request helpers. Runs under
 * `node --experimental-strip-types --test`. No Pi runtime, no network.
 *
 * What we pin:
 *
 *   1. `conversation_id` in the request wins over any hash. Two
 *      requests inside the same conversation MUST route to the same
 *      Pi session across a page reload.
 *   2. When there's no `conversation_id`, the hash of the first
 *      system/user message groups requests from the same
 *      conversation. Two DIFFERENT conversations from the same user
 *      must land on different keys.
 *   3. Different users on the same conversation content still get
 *      separate keys — the household model is shared memory in Pi,
 *      not shared conversations in LibreChat.
 *   4. `pickNextUserTurn` returns the newest user turn beyond a
 *      count, never accumulates them, and returns undefined when
 *      the caller has already forwarded the tail. That's what
 *      stops LibreChat's resent history from being replayed into
 *      Pi on every request.
 */
import { strict as assert } from "node:assert";
import { test } from "node:test";

import {
  pickNextUserTurn,
  sessionKey,
  type ChatCompletionRequest,
} from "./request.ts";

test("sessionKey: explicit conversation_id wins", () => {
  const a: ChatCompletionRequest = {
    messages: [{ role: "user", content: "hi" }],
    user: "sarah",
    conversation_id: "conv-1",
  };
  const b: ChatCompletionRequest = {
    messages: [{ role: "user", content: "totally different content" }],
    user: "sarah",
    conversation_id: "conv-1",
  };
  assert.equal(sessionKey(a), sessionKey(b));
  assert.ok(sessionKey(a).startsWith("conv:sarah:"));
});

test("sessionKey: hash of first message when no conversation_id", () => {
  const a: ChatCompletionRequest = {
    messages: [
      { role: "system", content: "You are Pi." },
      { role: "user", content: "hi" },
    ],
    user: "sarah",
  };
  const b: ChatCompletionRequest = {
    messages: [
      { role: "system", content: "You are Pi." },
      { role: "user", content: "hi" },
      { role: "assistant", content: "hello!" },
      { role: "user", content: "how are you" },
    ],
    user: "sarah",
  };
  assert.equal(
    sessionKey(a),
    sessionKey(b),
    "same conversation, message tail grows, key stays stable",
  );
});

test("sessionKey: different conversations from the same user get different keys", () => {
  const a: ChatCompletionRequest = {
    messages: [{ role: "user", content: "totally new thread" }],
    user: "sarah",
  };
  const b: ChatCompletionRequest = {
    messages: [{ role: "user", content: "a different thread" }],
    user: "sarah",
  };
  assert.notEqual(sessionKey(a), sessionKey(b));
});

test("sessionKey: same content from different users gets different keys", () => {
  const a: ChatCompletionRequest = {
    messages: [{ role: "user", content: "identical opener" }],
    user: "sarah",
  };
  const b: ChatCompletionRequest = {
    messages: [{ role: "user", content: "identical opener" }],
    user: "bruce",
  };
  assert.notEqual(sessionKey(a), sessionKey(b));
});

test("sessionKey: missing user falls back to anon but still keys stable", () => {
  const a: ChatCompletionRequest = {
    messages: [{ role: "user", content: "hi" }],
  };
  const b: ChatCompletionRequest = {
    messages: [{ role: "user", content: "hi" }],
  };
  assert.equal(sessionKey(a), sessionKey(b));
  assert.ok(sessionKey(a).includes(":anon:"));
});

test("pickNextUserTurn: returns the newest user message beyond the count", () => {
  const msgs: ChatCompletionRequest["messages"] = [
    { role: "system", content: "You are Pi." },
    { role: "user", content: "hi" },
    { role: "assistant", content: "hello!" },
    { role: "user", content: "how are you" },
  ];
  assert.equal(pickNextUserTurn(msgs, 1), "how are you");
});

test("pickNextUserTurn: returns undefined when caller has already forwarded the tail", () => {
  const msgs: ChatCompletionRequest["messages"] = [
    { role: "user", content: "hi" },
    { role: "assistant", content: "hello" },
  ];
  // 1 user turn total, 1 already forwarded -> nothing new
  assert.equal(pickNextUserTurn(msgs, 1), undefined);
});

test("pickNextUserTurn: ignores system and assistant messages", () => {
  const msgs: ChatCompletionRequest["messages"] = [
    { role: "system", content: "sys" },
    { role: "assistant", content: "asst" },
    { role: "user", content: "the one that matters" },
  ];
  assert.equal(pickNextUserTurn(msgs, 0), "the one that matters");
});

test("pickNextUserTurn: never returns older turns to avoid re-prompting Pi", () => {
  // LibreChat resends the whole history. If we forwarded the wrong
  // turn, Pi would answer the same question twice. This is the
  // regression this test exists to catch.
  const msgs: ChatCompletionRequest["messages"] = [
    { role: "user", content: "first question" },
    { role: "assistant", content: "first answer" },
    { role: "user", content: "second question" },
    { role: "assistant", content: "second answer" },
    { role: "user", content: "third question" },
  ];
  assert.equal(pickNextUserTurn(msgs, 2), "third question");
});
