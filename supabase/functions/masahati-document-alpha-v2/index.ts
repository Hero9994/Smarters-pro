import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { clip, understandDocument } from "./document-understanding.ts";
import { limitedReading } from "./document-fallback.ts";
import { semanticReading } from "./document-provider.ts";

const PUBLISHABLE_KEY = "sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3";
const HEADERS = { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" };
const out = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: HEADERS });
const MAX_TEXT = 24000;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: { ...HEADERS, "Access-Control-Allow-Headers": "apikey, content-type" } });
  if (req.method !== "POST") return out({ ok: false, error: "method_not_allowed" }, 405);
  if ((req.headers.get("apikey") ?? "") !== PUBLISHABLE_KEY) return out({ ok: false, error: "unauthorized" }, 401);
  // Existing public client key is compatibility gating, NOT user authentication. No paid provider is enabled.
  let body: any;
  try {
    const text = await req.text();
    if (text.length > 140000) return out({ ok: false, error: "document_too_large" }, 413);
    body = JSON.parse(text);
  } catch { return out({ ok: false, error: "invalid_json" }, 400); }
  const displayName = clip(body?.displayName, 180), source = clip(body?.ocrText, MAX_TEXT);
  if (!displayName && !source) return out({ ok: false, error: "empty_document" }, 400);
  const options = {
    extractionNote: clip(body?.extractionNote, 600),
    truncated: (typeof body?.ocrText === "string" && body.ocrText.length > MAX_TEXT) || body?.sourceTruncated === true,
  };
  if (source) {
    try {
      const result = await semanticReading(source, key => Deno.env.get(key));
      return out(understandDocument(result.raw, source, displayName, { ...options, method: "semantic", model: result.model }));
    } catch (error) {
      const reason = error instanceof Error ? error.message : "provider_unavailable";
      const allowed = ["provider_capacity", "provider_unavailable", "incomplete_model_output", "invalid_model_output", "incomplete_provider_configuration", "invalid_provider_url",
        "ambiguous_provider_configuration", "free_tier_not_confirmed", "unapproved_free_model", "custom_provider_disabled"];
      return out(understandDocument(limitedReading(source), source, displayName, {
        ...options, method: "rules", model: "rules-document-v3", failure: allowed.includes(reason) ? reason : "analysis_unavailable",
      }));
    }
  }
  return out(understandDocument({}, source, displayName, { ...options, method: "rules", model: "none" }));
});
