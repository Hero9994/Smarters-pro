import { resolveChatProvider } from "../_shared/chat-provider.ts";

export function providerConfig(env: (key: string) => string | undefined, quality = false) {
  return resolveChatProvider(env, quality ? "nvidia/nemotron-3-ultra-550b" : "nvidia/nemotron-3.5-lightning");
}
