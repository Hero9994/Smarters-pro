import test from "node:test";
import assert from "node:assert/strict";
import { providerConfig } from "./provider.ts";

test("existing provider remains default without opening an account or charging a new service", () => {
  const config = providerConfig(() => undefined);
  assert.equal(config.url, "https://blockrun.ai/api/v1/chat/completions");
  assert.equal(config.headers.Authorization, undefined);
});
test("configured provider uses only server values and partial configuration does not leak to fallback", () => {
  const values: Record<string,string> = {MASAHATI_CHAT_URL:"https://example.org/v1/chat/completions",MASAHATI_CHAT_MODEL:"test-model",MASAHATI_CHAT_API_KEY:"synthetic-test-key"};
  assert.equal(providerConfig(k => values[k]).headers.Authorization, "Bearer synthetic-test-key");
  assert.throws(() => providerConfig(k => k === "MASAHATI_CHAT_MODEL" ? "model" : undefined), /incomplete/);
  values.MASAHATI_CHAT_URL = "http://example.org/v1/chat/completions";
  assert.throws(() => providerConfig(k => values[k]), /invalid/);
});
