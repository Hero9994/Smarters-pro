import test from "node:test";
import assert from "node:assert/strict";
import { providerConfig, requestConversation, unavailableReply } from "./provider.ts";

test("existing provider remains default without opening an account or charging a new service", () => {
  const config = providerConfig(() => undefined);
  assert.equal(config.url, "https://blockrun.ai/api/v1/chat/completions");
  assert.equal(config.headers.Authorization, undefined);
});
test("configured provider uses only server values and partial configuration does not leak to fallback", () => {
  const values: Record<string,string> = {MASAHATI_CHAT_URL:"https://example.org/v1/chat/completions",MASAHATI_CHAT_MODEL:"test-model",MASAHATI_CHAT_API_KEY:"synthetic-test-key",MASAHATI_CUSTOM_PROVIDER_ENABLED:"true"};
  assert.equal(providerConfig(k => values[k]).headers.Authorization, "Bearer synthetic-test-key");
  assert.throws(() => providerConfig(k => k === "MASAHATI_CHAT_MODEL" ? "model" : undefined), /incomplete/);
  values.MASAHATI_CHAT_URL = "http://example.org/v1/chat/completions";
  assert.throws(() => providerConfig(k => values[k]), /invalid/);
});

test("conversation failure reports safe HTTP reasons and never returns upstream secrets or actions", async () => {
  const provider = providerConfig(() => undefined);
  for (const [status, reason] of [[401, "provider_auth"], [403, "provider_auth"], [429, "provider_capacity"], [400, "provider_request"], [503, "provider_unavailable"], [504, "provider_timeout"]] as const) {
    const result = await requestConversation(provider, 1000, [], (async () => new Response('private upstream content synthetic-secret', { status })) as typeof fetch);
    assert.deepEqual(result, { ok: false, error: reason });
  }
  assert.match(unavailableReply("provider_capacity"), /حد الاستخدام/);
  assert.match(unavailableReply("provider_timeout"), /وقتاً/);
  assert.match(unavailableReply("provider_auth"), /إعدادات الخادم/);
});

test("conversation distinguishes truncated, malformed and blocked replies without treating them as model success", async () => {
  const provider = providerConfig(() => undefined);
  const envelope = (finish_reason: string, content: string) => ({ model: "actual-model", choices: [{ finish_reason, message: { content } }] });
  for (const [body, error] of [
    ["not json", "invalid_model_output"],
    [JSON.stringify(envelope("stop", "not json")), "invalid_model_output"],
    [JSON.stringify(envelope("length", '{"reply":"unfinished"}')), "incomplete_model_output"],
    [JSON.stringify(envelope("content_filter", "")), "provider_blocked"],
  ]) {
    assert.deepEqual(await requestConversation(provider, 1000, [], (async () => new Response(body)) as typeof fetch), { ok: false, error });
  }
  const result = await requestConversation(provider, 1000, [], (async () => Response.json(envelope("stop", JSON.stringify({ reply: "اسم غير موجود", actions: [{ type: "create_space" }] })))) as typeof fetch);
  assert.deepEqual(result, { ok: true, model: "actual-model", parsed: { reply: "اسم غير موجود", actions: [] } });
});

test("conversation timeout and unexpected errors are sanitized", async () => {
  const provider = providerConfig(() => undefined);
  assert.deepEqual(await requestConversation(provider, 1000, [], (async () => { throw new DOMException("synthetic private content", "TimeoutError"); }) as typeof fetch), { ok: false, error: "provider_timeout" });
  assert.deepEqual(await requestConversation(provider, 1000, [], (async () => { throw new Error("Bearer synthetic-secret"); }) as typeof fetch), { ok: false, error: "provider_unavailable" });
});
