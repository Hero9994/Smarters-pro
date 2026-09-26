import test from "node:test";
import assert from "node:assert/strict";
import { providerConfig } from "../masahati-agent-dev/provider.ts";
import { documentProvider, semanticReading } from "../masahati-document-alpha-v2/document-provider.ts";
import { chatRequest } from "./chat-provider.ts";

const environment = (values: Record<string, string>) => (key: string) => values[key];

test("free route needs confirmed project tier and a reviewed model for both endpoints", () => {
  for (const resolve of [providerConfig, documentProvider]) {
    assert.throws(() => resolve(environment({ MASAHATI_GEMINI_API_KEY: "test-only" })), /free_tier_not_confirmed/);
    assert.throws(() => resolve(environment({ MASAHATI_GEMINI_MODEL: "gemini-3.1-flash-lite" })), /incomplete/);
    assert.throws(() => resolve(environment({ MASAHATI_GEMINI_API_KEY: "test-only", MASAHATI_GEMINI_FREE_TIER_CONFIRMED: "true", MASAHATI_GEMINI_MODEL: "paid-or-unknown-model" })), /unapproved_free_model/);
    const p = resolve(environment({ MASAHATI_GEMINI_API_KEY: "test-only", MASAHATI_GEMINI_FREE_TIER_CONFIRMED: "true" }));
    assert.equal(p.kind, "gemini_free");
    assert.equal(p.url, "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
    assert.equal(p.model, "gemini-3.1-flash-lite");
  }
});

test("no unapproved custom provider or silent ambiguous routing", () => {
  const custom = { MASAHATI_CHAT_URL: "https://example.org/v1/chat/completions", MASAHATI_CHAT_MODEL: "model", MASAHATI_CHAT_API_KEY: "test-only" };
  for (const resolve of [providerConfig, documentProvider]) {
    assert.throws(() => resolve(environment(custom)), /custom_provider_disabled/);
    assert.throws(() => resolve(environment({ ...custom, MASAHATI_GEMINI_API_KEY: "another-test-key" })), /ambiguous/);
    for (const url of ["http://example.org", "https://user:password@example.org/", "https://example.org/?key=secret", "https://example.org/#key", "not-a-url"]) {
      assert.throws(() => resolve(environment({ ...custom, MASAHATI_CHAT_URL: url, MASAHATI_CUSTOM_PROVIDER_ENABLED: "true" })), /invalid_provider_url/);
    }
  }
});

test("Gemini request keeps credentials out of content and preserves source/actual model", async () => {
  const env = environment({ MASAHATI_GEMINI_API_KEY: "synthetic-private-key", MASAHATI_GEMINI_FREE_TIER_CONFIRMED: "true" });
  let requests = 0;
  const fakeFetch = async (url: any, init: any) => {
    requests++;
    assert.equal(url, "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
    assert.equal(init.redirect, "error");
    assert.equal(init.headers.Authorization, "Bearer synthetic-private-key");
    assert.ok(!init.body.includes("synthetic-private-key"));
    const body = JSON.parse(init.body);
    assert.equal(body.reasoning_effort, "low");
    assert.ok(body.max_tokens > 2200);
    assert.equal(JSON.parse(body.messages.at(-1).content).ocr_text, "Rechnung\nOffener Betrag: 0 EUR");
    return Response.json({ model: "gemini-reported-version", choices: [{ finish_reason: "stop", message: { content: '{"doc_type":"invoice"}' } }] });
  };
  const result = await semanticReading("Rechnung\nOffener Betrag: 0 EUR", env, fakeFetch as typeof fetch);
  assert.equal(result.model, "gemini-reported-version");
  assert.equal(requests, 1);
  const chat = chatRequest(providerConfig(env), [{ role: "user", content: "وين المفتاح؟" }], "conversation");
  assert.equal(chat.reasoning_effort, "low");
  assert.equal(chat.messages.length, 1);
});

test("Gemini quota exhaustion makes one request and does not reroute to another provider", async () => {
  const env = environment({ MASAHATI_GEMINI_API_KEY: "test-only", MASAHATI_GEMINI_FREE_TIER_CONFIRMED: "true" });
  let requests = 0;
  await assert.rejects(() => semanticReading("Rechnung", env, (async () => {
    requests++;
    return new Response("{}", { status: 429 });
  }) as typeof fetch), /provider_capacity/);
  assert.equal(requests, 1);
});
