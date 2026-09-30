import { resolveChatProvider, chatRequest, fetchChat, providerErrorReason, providerHttpReason } from "../_shared/chat-provider.ts";
import type { ChatProvider } from "../_shared/chat-provider.ts";
import { parseModelEnvelope } from "./conversation.ts";

export function providerConfig(env: (key: string) => string | undefined, quality = false) {
  return resolveChatProvider(env, quality ? "nvidia/nemotron-3-ultra-550b" : "nvidia/nemotron-3.5-lightning");
}

export async function requestConversation(provider: ChatProvider, timeoutMs: number, messages: unknown[], fetcher = fetch) {
  try {
    const response = await fetchChat(provider, chatRequest(provider, messages, "conversation"), timeoutMs, fetcher);
    if (!response.ok) return { ok: false as const, error: providerHttpReason(response.status) };
    let envelope: any;
    try { envelope = await response.json(); }
    catch (error) {
      if (error instanceof SyntaxError) return { ok: false as const, error: "invalid_model_output" };
      throw error;
    }
    const choice = envelope?.choices?.[0];
    if (choice?.finish_reason === "content_filter" || envelope?.promptFeedback?.blockReason) {
      return { ok: false as const, error: "provider_blocked" };
    }
    if (choice?.finish_reason !== "stop") return { ok: false as const, error: "incomplete_model_output" };
    const result = parseModelEnvelope(envelope);
    return result ? { ok: true as const, ...result } : { ok: false as const, error: "invalid_model_output" };
  } catch (error) { return { ok: false as const, error: providerErrorReason(error) }; }
}

export function unavailableReply(reason: string): string {
  if (reason === "provider_capacity" || reason === "app_capacity") return "بلغت الخدمة حد الاستخدام المؤقت. بقيت رسالتك محفوظة؛ جرّب لاحقاً.";
  if (reason === "provider_timeout") return "استغرق رد المساعد وقتاً أطول من المتاح. بقيت رسالتك محفوظة، ويمكنك إعادة المحاولة.";
  if (["provider_auth", "incomplete_provider_configuration", "free_tier_not_confirmed", "ambiguous_provider_configuration", "unapproved_free_model", "invalid_provider_url", "custom_provider_disabled"].includes(reason)) {
    return "اتصال المساعد يحتاج مراجعة إعدادات الخادم. بقيت رسالتك محفوظة.";
  }
  return "تعذر الحصول على جواب من المساعد الآن. بقيت رسالتك محفوظة، ويمكنك إعادة المحاولة.";
}
