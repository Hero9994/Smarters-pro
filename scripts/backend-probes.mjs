// Synthetic probes only. No user documents, credentials or upstream raw bodies in reports.
export const SERVICE_BASE = "https://hxrvlvqlkfylbjicdfzs.supabase.co/functions/v1";
export const PUBLISHABLE_KEY = "sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3";
export const BACKEND_PROBES = [
  {
    name: "german_contract", kind: "document", endpoint: "masahati-document-alpha-v2", timeoutMs: 32000,
    request: { displayName: "Mietvertrag-MV4488.pdf", mimeType: "application/pdf", ocrText:
      "MIETVERTRAG. Vermieter: Beispiel GmbH. Mieter: Max Mustermann. Vertragsnummer MV-4488. Vertragsende 30.09.2027. Der Mieter muss spätestens bis 15.09.2027 schriftlich kündigen." },
    assertions: { ok: true, "document.doc_type": "contract", "document.reference_number": "MV-4488",
      "document.expiry_date": "2027-09-30", "document.due_date": "2027-09-15", "document.action_required": true,
      analysis_method: "semantic", "document.analysis_method": "semantic" },
  },
  {
    name: "arabic_contract", kind: "document", endpoint: "masahati-document-alpha-v2", timeoutMs: 32000,
    request: { displayName: "عقد.pdf", mimeType: "application/pdf", ocrText:
      "عقد إيجار. الشركة: شركة المثال. رقم العقد: AR-991. تاريخ الانتهاء: 30.09.2027. آخر موعد: 15.09.2027. يجب إرسال طلب الإلغاء قبل الموعد." },
    assertions: { ok: true, "document.doc_type": "contract", "document.reference_number": "AR-991",
      "document.expiry_date": "2027-09-30", "document.due_date": "2027-09-15", "document.action_required": true,
      analysis_method: "semantic", "document.analysis_method": "semantic" },
  },
  {
    name: "agent_search", kind: "agent", endpoint: "masahati-agent-dev", timeoutMs: 15000,
    request: { text: "وين حطيت عقد الإيجار؟", spaceTitle: "تجربة", mode: "chat", now: "2026-09-06T08:00:00+02:00",
      timezone: "Europe/Berlin", appState: { currentSpaceId: 1, currentSpaceTitle: "تجربة", spaces: [{ id: 1, title: "تجربة" }], memory: [] }, recent: [] },
    assertions: { ok: true, classification: "search" }, searchQuery: "عقد الإيجار",
  },
  {
    name: "agent_contract_expiry", kind: "agent", endpoint: "masahati-agent-dev", timeoutMs: 15000,
    request: { text: "متى بينتهي هاد العقد؟", spaceTitle: "عقودي", mode: "chat", now: "2026-09-06T08:00:00+02:00", timezone: "Europe/Berlin",
      appState: { currentSpaceId: 1, currentSpaceTitle: "عقودي", spaces: [{ id: 1, title: "عقودي" }], memory: [],
        currentDocument: { id: 22, displayName: "Mietvertrag.pdf", classification: "document", tags: "عقد", summary: "Mietvertrag",
          ocrText: "MIETVERTRAG. Vertragsnummer MV-4488. Vertragsende 30.09.2027.", createdAt: 1788670000000 } },
      recent: [{ role: "user", kind: "file", displayName: "Mietvertrag.pdf", classification: "document", tags: "عقد", summary: "Mietvertrag", text: "",
        ocrText: "MIETVERTRAG. Vertragsnummer MV-4488. Vertragsende 30.09.2027.", createdAt: 1788670000000 }] },
    assertions: { ok: true }, replyContains: "30.09.2027",
  },
];

const semanticFields = new Set(["analysis_method", "document.analysis_method"]);
const transientReasons = new Set(["provider_capacity", "provider_timeout", "provider_unavailable"]);
const stableCode = value => typeof value === "string" && /^[a-z][a-z0-9_]{0,99}$/.test(value) ? value : null;
const field = (body, path) => path.split(".").reduce((value, key) => value?.[key], body) ?? null;

/** Compare facts even when HTTP succeeds; fallback is NEVER semantic success.
 * A wrong fact remains data_mismatch even if a transient fallback also occurred.
 * Stable provider codes describe the response, not a retrospective explanation
 * for a past run whose response was not saved.
 */
export function inspectProbe(probe, { httpStatus = 200, body, transportError = null, elapsedMs = 0 } = {}) {
  const expected = { ...probe.assertions }, actual = {}, comparisons = [];
  for (const [path, value] of Object.entries(expected)) {
    actual[path] = field(body, path);
    comparisons.push({ field: path, expected: value, actual: actual[path], passed: Object.is(value, actual[path]) });
  }
  if (probe.searchQuery) {
    const queries = Array.isArray(body?.actions) ? body.actions.filter(a => a?.type === "search").map(a => a?.args?.query ?? null) : [];
    expected["actions.search_query"] = probe.searchQuery; actual["actions.search_query"] = queries;
    comparisons.push({ field: "actions.search_query", expected: probe.searchQuery, actual: queries, passed: queries.includes(probe.searchQuery) });
  }
  if (probe.replyContains) {
    const reply = typeof body?.reply === "string" ? body.reply : null;
    expected["reply.contains"] = probe.replyContains; actual["reply.contains"] = reply;
    comparisons.push({ field: "reply.contains", expected: probe.replyContains, actual: reply, passed: reply?.includes(probe.replyContains) === true });
  }
  const mismatches = comparisons.filter(c => !c.passed);
  const metadata = {
    analysis_method: body?.analysis_method ?? null, document_analysis_method: body?.document?.analysis_method ?? null,
    analysis_status: body?.analysis_status ?? null, model: body?.model ?? null, engine: body?.engine ?? null,
    degraded_reason: body?.degraded_reason === "" ? "" : stableCode(body?.degraded_reason),
    issue_codes: Array.isArray(body?.document?.issue_codes) ? body.document.issue_codes.map(stableCode).filter(Boolean) : [],
  };
  let status = "passed", exitCode = 0, transient = false;
  if (transportError) { status = "transport_unavailable"; exitCode = 2; transient = true; }
  else if (httpStatus < 200 || httpStatus >= 300) {
    transient = [408, 425, 429, 500, 502, 503, 504].includes(httpStatus);
    status = transient ? "http_temporarily_unavailable" : "http_failure"; exitCode = transient ? 2 : 1;
  } else if (!body || typeof body !== "object" || Array.isArray(body)) { status = "invalid_response"; exitCode = 1; }
  else if (mismatches.some(c => !semanticFields.has(c.field))) { status = "data_mismatch"; exitCode = 1; }
  else if (mismatches.length) {
    transient = metadata.analysis_method !== "semantic" && transientReasons.has(metadata.degraded_reason);
    status = transient ? "semantic_temporarily_unavailable" : "semantic_required"; exitCode = transient ? 2 : 1;
  }
  return { case: probe.name, passed: status === "passed", status, exit_code: exitCode, transient,
    http_status: httpStatus, transport_error: stableCode(transportError), elapsed_ms: elapsedMs,
    expected, actual, mismatches, ...metadata };
}

export function reportExitCode(results) {
  // A data/configuration failure is never hidden by an unrelated provider outage.
  return results.some(r => r.exit_code === 1) ? 1 : results.some(r => r.exit_code !== 0) ? 2 : 0;
}
