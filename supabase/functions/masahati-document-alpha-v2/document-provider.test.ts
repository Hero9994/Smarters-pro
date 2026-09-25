import { test } from "node:test";
import assert from "node:assert/strict";
import { documentProvider, parseModelResponse, semanticReading } from "./document-provider.ts";

test("provider uses server config exclusively and fails closed on partial configuration", () => {
  assert.ok(documentProvider(() => undefined).model.includes("nano-omni"));
  assert.throws(() => documentProvider(k => k === "MASAHATI_CHAT_API_KEY" ? "server-key" : undefined));
  assert.throws(() => documentProvider(k => ({ MASAHATI_CHAT_URL: "http://example.com", MASAHATI_CHAT_API_KEY: "key", MASAHATI_CHAT_MODEL: "model" })[k]));
});

test("truncated model JSON is rejected and actual reported model is retained", () => {
  const data = { model: "actual-model", choices: [{ finish_reason: "stop", message: { content: '{"doc_type":"invoice"}' } }] };
  assert.equal(parseModelResponse(data).model, "actual-model");
  data.choices[0].finish_reason = "length";
  assert.throws(() => parseModelResponse(data));
});

test("semantic path sends OCR as data once with bounded timeout and reports capacity failure", async () => {
  let requests = 0;
  const fakeFetch = async (_url: any, init: any) => {
    requests++;
    assert.ok(init.signal);
    const body = JSON.parse(init.body);
    assert.equal(JSON.parse(body.messages[1].content).ocr_text, "Rechnung");
    assert.ok(body.messages[0].content.includes("untrusted data"));
    return new Response('{}', { status: 429 });
  };
  await assert.rejects(() => semanticReading("Rechnung", () => undefined, fakeFetch as typeof fetch), /provider_capacity/);
  assert.equal(requests, 1);
});
