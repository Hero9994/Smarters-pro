// Synthetic documents only. Default: limited rules; --live: deployed endpoint.
// --require-semantic fails when the provider falls back; fallback success is NOT an LLM benchmark.
import { limitedReading } from "../supabase/functions/masahati-document-alpha-v2/document-fallback.ts";
import { understandDocument } from "../supabase/functions/masahati-document-alpha-v2/document-understanding.ts";
const live = process.argv.includes("--live");
const cases = [
  { name: "cancellation", type: "cancellation_confirmation", text: "Kündigungsbestätigung\nVertragsnummer: AB-12345\nWir bestätigen die Kündigung Ihres Vertrags zum 31.12.2026. Es besteht kein Handlungsbedarf.", action: false },
  { name: "paid_invoice", type: "invoice", text: "Rechnung\nRechnungsnummer: R-123\nGesamtbetrag: 119,00 EUR\nBereits bezahlt: 119,00 EUR\nOffener Betrag: 0,00 EUR\nBitte nicht erneut überweisen.", amount: "0,00 EUR", action: false },
  { name: "benefit_refusal", type: "benefits_notice", text: "Ablehnungsbescheid\nIhr Antrag auf Wohngeld wird abgelehnt. Der Mietvertrag wurde geprüft. Sie können innerhalb eines Monats Widerspruch einreichen.", action: false },
  { name: "medical_transport", type: "medical_transport", text: "Verordnung einer Krankenbeförderung\nPatient: Max Beispiel\nTransport zur ambulanten Behandlung. Kein Arbeitsvertrag.", action: false },
  { name: "salary", type: "payslip", text: "Entgeltabrechnung\nArbeitsvertrag AV-123\nNetto: 2.200,00 EUR\nDie Auszahlung erfolgt automatisch.", amount: "2.200,00 EUR", action: false },
  { name: "school_arabic", type: "school", text: "دعوة إلى اجتماع أولياء الأمور\nالموعد: ١٠.١٠.٢٠٢٦ الساعة 17:30\nيرجى إحضار استمارة بيانات الطوارئ.", action: true, date: "2026-10-10" },
  { name: "invalid_date", type: "contract", text: "Mietvertrag\nVertragsende 31.02.2027\nVertragsnummer: MV-999", action: false, invalidDate: true },
];
let failed = 0, semantic = 0, limited = 0;
for (const c of cases.filter(c => !process.argv.find(a => a.startsWith("--case=")) || c.name === process.argv.find(a => a.startsWith("--case=")).slice(7))) {
  const started = Date.now();
  try {
    let result;
    if (live) {
      const response = await fetch("https://hxrvlvqlkfylbjicdfzs.supabase.co/functions/v1/masahati-document-alpha-v2", {
        method: "POST", headers: { "Content-Type": "application/json", apikey: "sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3" },
        body: JSON.stringify({ displayName: "scan.pdf", ocrText: c.text }), signal: AbortSignal.timeout(32000),
      });
      if (!response.ok) throw new Error(`http_${response.status}`);
      result = await response.json();
    } else result = understandDocument(limitedReading(c.text), c.text, "scan.pdf", { method: "rules", model: "rules-document-v3" });
    const d = result.document;
    const isSemantic = result.analysis_method === "semantic";
    if (isSemantic) semantic++; else limited++;
    const passed = result.ok && d.doc_type === c.type && d.action_required === c.action &&
      (!c.amount || d.amount_text === c.amount) && (!c.date || d.dates.some(x => x.role === "appointment" && x.value === c.date)) &&
      (!c.invalidDate || (!d.expiry_date && d.needs_date_review)) &&
      (!process.argv.includes("--require-semantic") || isSemantic);
    if (!passed) failed++;
    console.log(JSON.stringify({ case: c.name, passed, method: result.analysis_method, model: result.model,
      type: d.doc_type, amount: d.amount_text, action: d.action_required, issues: d.issue_codes,
      degradedReason: result.degraded_reason, ms: Date.now() - started }));
  } catch (error) { failed++; console.log(JSON.stringify({ case: c.name, passed: false, error: error.message })); }
}
console.log(JSON.stringify({ semantic, limited, failed, note: "Rule fallback results do not establish model reasoning quality." }));
process.exitCode = failed ? 1 : 0;
