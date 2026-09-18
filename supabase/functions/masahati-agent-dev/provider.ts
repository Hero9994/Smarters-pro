/** Server configuration only: requests from Android cannot choose an endpoint, model or API key. */
export function providerConfig(env: (key: string) => string | undefined, quality = false) {
  const url = env("MASAHATI_CHAT_URL")?.trim();
  const model = env("MASAHATI_CHAT_MODEL")?.trim();
  const key = env("MASAHATI_CHAT_API_KEY")?.trim();
  if (!url && !model && !key) return {
    url: "https://blockrun.ai/api/v1/chat/completions",
    model: quality ? "nvidia/nemotron-3-ultra-550b" : "nvidia/nemotron-3.5-lightning",
    headers: { "Content-Type": "application/json", Accept: "application/json" } as Record<string, string>,
  };
  // Fail closed on partial configuration. Never silently send documents to another service.
  if (!url || !model || !key) throw new Error("incomplete_provider_configuration");
  const endpoint = new URL(url);
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new Error("invalid_provider_url");
  }
  return { url: endpoint.toString(), model,
    headers: { "Content-Type": "application/json", Accept: "application/json", Authorization: "Bearer " + key } };
}
