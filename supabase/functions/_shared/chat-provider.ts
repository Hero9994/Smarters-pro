/** Server-only routing. Never accept provider settings or credentials from an Android request. */
type Environment = (key: string) => string | undefined;
export type ChatProvider = {
  url: string;
  model: string;
  kind: "public_free" | "gemini_free" | "custom";
  headers: Record<string, string>;
};

// Free-tier text models reviewed against Google's pricing on 2026-09-26.
// A model name alone does NOT prove a Google project has billing disabled.
const FREE_GEMINI_MODELS = new Set(["gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.5-flash"]);

export function resolveChatProvider(env: Environment, defaultModel: string): ChatProvider {
  const value = (key: string) => env(key)?.trim() ?? "";
  const url = value("MASAHATI_CHAT_URL"), model = value("MASAHATI_CHAT_MODEL"), key = value("MASAHATI_CHAT_API_KEY");
  const geminiKey = value("MASAHATI_GEMINI_API_KEY"), geminiModel = value("MASAHATI_GEMINI_MODEL");
  const freeConfirmed = value("MASAHATI_GEMINI_FREE_TIER_CONFIRMED");
  const customConfigured = !!(url || model || key);
  const geminiConfigured = !!(geminiKey || geminiModel || freeConfirmed);
  if (customConfigured && geminiConfigured) throw new Error("ambiguous_provider_configuration");
  const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "application/json" };
  if (geminiConfigured) {
    if (!geminiKey) throw new Error("incomplete_provider_configuration");
    if (freeConfirmed !== "true") throw new Error("free_tier_not_confirmed");
    const selectedModel = geminiModel || "gemini-3.1-flash-lite";
    if (!FREE_GEMINI_MODELS.has(selectedModel)) throw new Error("unapproved_free_model");
    return { kind: "gemini_free", url: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
      model: selectedModel, headers: { ...headers, Authorization: "Bearer " + geminiKey } };
  }
  if (!customConfigured) return { kind: "public_free", url: "https://blockrun.ai/api/v1/chat/completions", model: defaultModel, headers };
  if (!url || !model || !key) throw new Error("incomplete_provider_configuration");
  let endpoint: URL;
  try { endpoint = new URL(url); } catch { throw new Error("invalid_provider_url"); }
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new Error("invalid_provider_url");
  }
  // The user currently authorized free use only. An arbitrary provider must be explicitly enabled later.
  if (value("MASAHATI_CUSTOM_PROVIDER_ENABLED") !== "true") throw new Error("custom_provider_disabled");
  return { kind: "custom", url: endpoint.toString(), model, headers: { ...headers, Authorization: "Bearer " + key } };
}

export function chatRequest(provider: ChatProvider, messages: unknown[], task: "document" | "conversation") {
  const gemini = provider.kind === "gemini_free";
  return { model: provider.model, messages, stream: false,
    temperature: gemini ? 1 : task === "document" ? 0 : 0.05,
    // Gemini's limit includes reasoning. Leave room for the complete JSON; reject truncated replies.
    max_tokens: gemini ? (task === "document" ? 6144 : 4096) : (task === "document" ? 2200 : 1050),
    ...(gemini ? { reasoning_effort: "low" } : {}),
  };
}

const PROVIDER_ERRORS = new Set([
  "provider_capacity", "provider_auth", "provider_request", "provider_unavailable", "provider_timeout", "provider_blocked",
  "incomplete_model_output", "invalid_model_output", "incomplete_provider_configuration", "invalid_provider_url",
  "ambiguous_provider_configuration", "free_tier_not_confirmed", "unapproved_free_model", "custom_provider_disabled",
]);

/** Return only stable codes, never upstream response bodies, credentials or document text. */
export function providerErrorReason(error: unknown): string {
  if (error instanceof Error) {
    if (error.name === "TimeoutError" || error.name === "AbortError") return "provider_timeout";
    if (PROVIDER_ERRORS.has(error.message)) return error.message;
  }
  return "provider_unavailable";
}

export function providerHttpReason(status: number): string {
  if (status === 429) return "provider_capacity";
  if (status === 401 || status === 403) return "provider_auth";
  if (status === 408 || status === 504) return "provider_timeout";
  if (status >= 400 && status < 500) return "provider_request";
  return "provider_unavailable";
}

/** Retry one explicit transient Gemini server failure, within the original deadline.
 * Quota/auth/client errors and other providers never get extra attempts or rerouting.
 */
export async function fetchChat(provider: ChatProvider, body: unknown, timeoutMs: number, fetcher = fetch) {
  const signal = AbortSignal.timeout(timeoutMs);
  const init = { method: "POST", headers: provider.headers, signal, redirect: "error" as const, body: JSON.stringify(body) };
  let response = await fetcher(provider.url, init);
  if (provider.kind === "gemini_free" && [500, 502, 503, 504].includes(response.status) && !signal.aborted) {
    await response.body?.cancel();
    await new Promise(resolve => setTimeout(resolve, 200));
    signal.throwIfAborted();
    response = await fetcher(provider.url, init);
  }
  return response;
}
