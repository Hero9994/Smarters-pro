import { test } from "node:test";
import assert from "node:assert/strict";
import { understandDocument } from "./document-understanding.ts";
import { limitedReading } from "./document-fallback.ts";

const read = (text: string, raw = limitedReading(text), extra = {}) => understandDocument(raw, text, "scan.pdf", { method: "semantic", model: "test-fixture", ...extra } as any);

test("cancellation, invoice, benefit refusal and medical transport are not contracts just because they mention one", () => {
  for (const [type, text] of [
    ["cancellation_confirmation", "Kündigungsbestätigung\nVertragsnummer: AB-12345\nWir bestätigen die Kündigung Ihres Vertrags zum 31.12.2026."],
    ["invoice", "Rechnung\nVertragsnummer: AB-12345\nGesamtbetrag: 119,00 EUR"],
    ["benefits_notice", "Ablehnungsbescheid\nIhr Antrag auf Wohngeld wird abgelehnt. Der Mietvertrag wurde geprüft."],
    ["medical_transport", "Verordnung einer Krankenbeförderung\nTransport zum Arzt. Versicherungsvertrag: KV-123."],
    ["payslip", "Entgeltabrechnung\nArbeitsvertrag AV-123\nNetto: 2.200,00 EUR"],
    ["bank_statement", "Kontoauszug\nBuchung: Rechnung zum Vertrag AB-123"],
  ]) assert.equal(read(text).document.doc_type, type, text);
});

test("paid invoice distinguishes total, paid and remaining and must not create payment task", () => {
  const text = "Rechnung\nGesamtbetrag: 119,00 EUR\nBereits bezahlt: 119,00 EUR\nOffener Betrag: 0,00 EUR\nBitte nicht erneut überweisen.";
  const result = read(text);
  assert.equal(result.document.amount_text, "0,00 EUR");
  assert.deepEqual(result.document.amounts.map(x => x.role), ["total", "paid", "due"]);
  assert.equal(result.document.action_required, false);
  assert.equal(result.document.action_status, "none");
});

test("a model cannot invent organization, numbers, dates or evidence even with confidence 1", () => {
  const text = "Rechnung\nRechnungsnummer: R-123\nRechnungsdatum: 12.09.2026";
  const result = read(text, { ...limitedReading(text), confidence: 1,
    organization: { value: "Fake GmbH", excerpt: "Fake GmbH" },
    references: [{ value: "R-999", excerpt: "Rechnungsnummer: R-123" }],
    dates: [{ role: "due", value: "2026-09-12", excerpt: "Rechnungsdatum: 12.09.2026" }],
    amounts: [{ role: "due", value: "123 EUR", excerpt: "Rechnungsnummer: R-123" }],
  });
  assert.equal(result.document.organization, "");
  assert.equal(result.document.reference_number, "");
  assert.equal(result.document.due_date, "");
  assert.equal(result.document.amount_text, "");
  assert.ok(result.confidence < 0.9);
  assert.equal(result.analysis_status, "needs_review");
});

test("negation omitted by a model and optional appeal rights cannot create tasks", () => {
  for (const [text, excerpt] of [
    ["Bitte nicht erneut überweisen.", "erneut überweisen."],
    ["Falls Sie kündigen möchten, müssen Sie schriftlich kündigen.", "müssen Sie schriftlich kündigen."],
    ["Sie können innerhalb eines Monats Widerspruch einreichen.", "Widerspruch einreichen."],
    ["لا يجب إرسال أي مبلغ.", "يجب إرسال أي مبلغ."],
  ]) {
    const result = read(text, { action: { status: "required", excerpt } });
    assert.equal(result.document.action_required, false, text);
  }
});

test("invoice amount roles cannot borrow an earlier label", () => {
  const text = "Gesamtbetrag: 119,00 EUR. Offener Betrag: 19,00 EUR";
  const result = read(text, { amounts: [{ role: "due", value: "119,00 EUR", excerpt: text }] });
  assert.equal(result.document.amount_text, "");
  assert.ok(result.document.issue_codes.includes("uncertain_amount"));
});

test("impossible, guessed and conflicting deadlines are review items, not scheduled dates", () => {
  const text = "Vertrag\nFrist bis 31.02.2027\nFrist bis 15.09.2027\nFrist bis 16.09.2027\nBitte Unterlagen einreichen.";
  const result = read(text);
  assert.equal(result.document.due_date, "");
  assert.equal(result.document.needs_date_review, true);
  assert.equal(result.document.action_required, false);
});

test("appointment and expiry are distinct from a payment deadline and Arabic numerals are preserved", () => {
  const text = "دعوة إلى اجتماع أولياء الأمور\nالموعد: ١٠.١٠.٢٠٢٦ الساعة 17:30\nيرجى إحضار استمارة بيانات الطوارئ.";
  const result = read(text);
  assert.equal(result.document.doc_type, "school");
  assert.equal(result.document.dates[0].value, "2026-10-10");
  assert.equal(result.document.dates[0].role, "appointment");
  assert.equal(result.document.due_date, "");
  assert.equal(result.document.action_required, true);
});

test("legacy German and Arabic contracts retain explicit distinct fields", () => {
  for (const text of [
    "MIETVERTRAG. Vermieter: Beispiel GmbH. Vertragsnummer MV-4488. Vertragsende 30.09.2027. Der Mieter muss spätestens bis 15.09.2027 schriftlich kündigen.",
    "عقد إيجار. الشركة: شركة المثال. رقم العقد: AR-991. تاريخ الانتهاء: 30.09.2027. آخر موعد: 15.09.2027. يجب إرسال طلب الإلغاء قبل الموعد.",
  ]) {
    const result = read(text);
    assert.equal(result.document.doc_type, "contract");
    assert.equal(result.document.expiry_date, "2027-09-30");
    assert.equal(result.document.due_date, "2027-09-15");
    assert.equal(result.document.action_required, true);
    assert.notEqual(result.document.reference_number, "");
  }
});

test("partial OCR, truncation and multiple documents suppress automatic tasks", () => {
  const text = "Rechnung\nBitte überweisen Sie den offenen Betrag.\nOffener Betrag: 50,00 EUR";
  for (const extra of [{ extractionNote: "page 2 unreadable" }, { truncated: true }]) {
    const result = read(text, limitedReading(text), extra);
    assert.equal(result.document.action_required, false);
    assert.equal(result.analysis_status, "needs_review");
  }
  assert.equal(read(text, { ...limitedReading(text), issues: ["multiple_documents"] }).document.action_required, false);
});

test("fallback and blank documents never advertise semantic analysis or near certainty", () => {
  const text = "Rechnung\nRechnungsnummer: A-123\nGesamtbetrag: 100,00 EUR\nFällig am 12.10.2026";
  const result = read(text, limitedReading(text), { method: "rules", model: "rules-document-v3" });
  assert.equal(result.analysis_status, "limited");
  assert.equal(result.confidence, 0.5);
  assert.equal(read("", {}).confidence, 0);
  assert.equal(read("", {}).document.doc_type, "other");
});
