import { normalizedDate } from "./document-dates.ts";

export const TYPES: Record<string, string> = {
  contract: "عقد", invoice: "فاتورة", receipt: "إيصال دفع", payment_reminder: "مطالبة بالدفع",
  cancellation_confirmation: "تأكيد إلغاء", cancellation_request: "طلب إلغاء",
  official_notice: "قرار رسمي", benefits_notice: "قرار إعانة", tax_notice: "قرار ضريبي",
  medical: "مستند طبي", prescription: "وصفة طبية", medical_transport: "وصفة نقل طبي",
  appointment: "موعد", school: "مراسلة مدرسية", payslip: "قسيمة راتب",
  employment: "مستند عمل", insurance: "مستند تأمين", bank_statement: "كشف حساب",
  warranty: "ضمان", identity: "وثيقة هوية", form: "استمارة", letter: "رسالة", other: "مستند غير محدد",
};
export const TOPICS: Record<string, string> = {
  housing: "السكن", health: "الصحة", education: "الدراسة", employment: "العمل",
  finance: "المال", insurance: "التأمين", government: "المعاملات الرسمية",
  telecom: "الاتصالات", shopping: "المشتريات", other: "غير محدد",
};
export const DATE_LABELS: Record<string, string> = {
  issue: "تاريخ الإصدار", due: "آخر موعد مطلوب", expiry: "تاريخ الانتهاء", appointment: "تاريخ الموعد",
  period_start: "بداية الفترة", period_end: "نهاية الفترة", birth: "تاريخ الميلاد", other: "تاريخ آخر",
};
export const AMOUNT_LABELS: Record<string, string> = {
  due: "المتبقي للدفع", total: "الإجمالي", paid: "المدفوع", refund: "المبلغ المسترد",
  salary: "صافي الراتب", balance: "رصيد الحساب", other: "مبلغ آخر",
};
export const clip = (v: unknown, n: number) => typeof v === "string" ? v.trim().slice(0, n) : "";
export function comparable(v: string) {
  return v.normalize("NFKC").replace(/[٠-٩۰-۹]/g, c => String(c.charCodeAt(0) % 16))
    .replace(/[\u200e\u200f\u061c]/g, "").replace(/\s+/g, " ").trim().toLowerCase();
}
export const DATE_PATTERN = /(?<!\d)(?:(?:19|20)\d{2}-\d{2}-\d{2}|\d{1,2}[./-]\d{1,2}[./-](?:19|20)\d{2})(?!\d)/g;
export const DATE_ROLES: Record<string, RegExp> = {
  issue: /ausstellungsdatum|ausgestellt am|rechnungsdatum|bescheiddatum|issued? (?:on|date)|تاريخ الإصدار|تاريخ الاصدار/iu,
  due: /fällig|faellig|zahlbar bis|frist(?:\s+bis)?|spätestens(?:\s+bis)?|spaetestens(?:\s+bis)?|(?:zahlen|überweisen|einreichen|vorlegen).{0,30}\bbis\b|due date|deadline|pay by|آخر موعد|اخر موعد|يجب قبل|تاريخ الاستحقاق/iu,
  expiry: /vertragsende|vertragsablauf|gültig bis|gueltig bis|ablaufdatum|endet(?:\s+am)?|kündigung.{0,55}zum|expiry|expires|valid until|تاريخ الانتهاء|صالح حتى|ينتهي في/iu,
  appointment: /termin|appointment|الموعد|موعد|الاجتماع/iu,
};
const NO_ACTION = /\b(?:kein(?:e|en)?|nicht|bereits bezahlt|already paid|no action|do not)\b|لا\s+(?:يجب|تدفع|توجد|يلزم)|غير مطلوب|تم الدفع|مدفوعة/iu;
const OPTIONAL = /\b(?:können|kann|dürfen|may|can|optional|falls|sofern|wenn)\b|يمكنك|يجوز|اختياري|إذا|اذا/iu;
const REQUIRED = /\b(?:muss|müssen|bitte|zahlen|überweisen|einreichen|vorlegen|submit|must|pay|required)\b|يجب|يرجى|ادفع|أرسل|إحضار/iu;
export function actionStatus(excerpt: string): "required" | "none" | "possible" | "unknown" {
  if (NO_ACTION.test(excerpt)) return "none";
  if (OPTIONAL.test(excerpt)) return "possible";
  return REQUIRED.test(excerpt) ? "required" : "unknown";
}

// Quotes must exist in the OCR. A model's own assertion that a fact has evidence is insufficient.
function quote(source: string, raw: unknown): string {
  const text = clip(raw, 500);
  return text.length >= 3 && comparable(source).includes(comparable(text)) ? text : "";
}
function inQuote(value: string, excerpt: string) {
  const escaped = comparable(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return value.length > 0 && new RegExp(`(?<![\\p{L}\\p{N}])${escaped}(?![\\p{L}\\p{N}])`, "u").test(comparable(excerpt));
}
function dateSupported(value: string, excerpt: string, role: string) {
  const text = comparable(excerpt);
  let previousEnd = 0;
  for (const match of text.matchAll(DATE_PATTERN)) {
    const prefix = text.slice(Math.max(previousEnd, match.index! - 100), match.index);
    previousEnd = match.index! + match[0].length;
    if (normalizedDate(match[0]) === value && (!DATE_ROLES[role] || DATE_ROLES[role].test(prefix))) return true;
  }
  return false;
}
const AMOUNT_ROLES: Record<string, RegExp> = {
  due: /offen(?:er|e|es)?(?: betrag)?|restbetrag|zu zahlen|zahlbetrag|amount due|balance due|المتبقي|المستحق|المطلوب دفعه/iu,
  total: /gesamt(?:betrag)?|rechnungsbetrag|total|الإجمالي|الاجمالي|المجموع/iu,
  paid: /bezahlt|gezahlt|paid|المدفوع|تم دفع/iu,
  refund: /erstattung|guthaben|refund|المسترد/iu,
  salary: /netto|auszahlungsbetrag|net pay|صافي/iu,
  balance: /kontostand|saldo|balance|رصيد/iu,
};
function amountSupported(value: string, excerpt: string, role: string) {
  if (!inQuote(value, excerpt) || !/\d/u.test(comparable(value))) return false;
  const text = comparable(excerpt), position = text.indexOf(comparable(value));
  // The nearest label before this amount must describe its role, not an earlier amount.
  const prefix = text.slice(Math.max(0, position - 65), position).split(/[\d\n]/).at(-1) ?? "";
  return role === "other" || !!AMOUNT_ROLES[role]?.test(prefix);
}
function completeActionQuote(source: string, excerpt: string) {
  if (!excerpt) return "";
  // A real substring can still omit "nicht" / "لا". Recover its complete sentence/line.
  const clause = source.split(/\n+|(?<=[.!?])\s+(?=[\p{L}])/u)
    .find(s => comparable(s).includes(comparable(excerpt)));
  return clause && clause.trim().length <= 500 ? clause.trim() : "";
}
type Evidence = { field: string; value: string; excerpt: string };
type Fact = { value: string; excerpt: string };
const list = (v: unknown): any[] => Array.isArray(v) ? v.slice(0, 16) : [];

/** One gate shared by semantic extraction and the conservative offline-service fallback. */
export function understandDocument(raw: any, source: string, displayName: string, options: {
  method: "semantic" | "rules"; model: string; extractionNote?: string; truncated?: boolean; failure?: string;
}) {
  const evidence: Evidence[] = [], issues = new Set<string>();
  // Do not let a model silently omit an impossible calendar date from otherwise plausible output.
  if ([...comparable(source).matchAll(DATE_PATTERN)].some(m => !normalizedDate(m[0]))) issues.add("uncertain_date");
  const readFact = (item: any, field: string): Fact | null => {
    if (!item?.value) return null;
    const value = clip(item.value, 160), excerpt = quote(source, item.excerpt);
    if (!excerpt || !inQuote(value, excerpt)) { issues.add("unsupported_field"); return null; }
    evidence.push({ field, value, excerpt });
    return { value, excerpt };
  };
  const typeQuote = quote(source, raw?.classification_excerpt);
  const docType = typeQuote && Object.hasOwn(TYPES, raw?.doc_type) ? raw.doc_type : "other";
  if (docType !== "other") evidence.push({ field: "doc_type", value: TYPES[docType], excerpt: typeQuote });
  else issues.add("uncertain_type");
  const topic = typeQuote && Object.hasOwn(TOPICS, raw?.topic) ? raw.topic : "other";
  const organization = readFact(raw?.organization, "organization")?.value ?? "";
  const people = list(raw?.person_names).map(p => readFact(p, "person_names")?.value).filter(Boolean);
  const references = list(raw?.references).map(r => {
    const fact = readFact(r, "reference_number");
    return fact ? { ...fact, kind: ["invoice", "contract", "customer", "case", "insurance"].includes(r.kind) ? r.kind : "other" } : null;
  }).filter(Boolean);
  const dates = list(raw?.dates).flatMap(d => {
    const value = normalizedDate(comparable(clip(d.value, 40))), excerpt = quote(source, d.excerpt);
    const role = Object.hasOwn(DATE_LABELS, d.role) ? d.role : "other";
    if (!value || !excerpt || !dateSupported(value, excerpt, role)) { issues.add("uncertain_date"); return []; }
    return [{ role, label: DATE_LABELS[role], value, excerpt }];
  });
  const amounts = list(raw?.amounts).flatMap(a => {
    const value = clip(a.value, 60), excerpt = quote(source, a.excerpt);
    const role = Object.hasOwn(AMOUNT_LABELS, a.role) ? a.role : "other";
    if (!excerpt || !amountSupported(value, excerpt, role)) { issues.add("uncertain_amount"); return []; }
    const currency = /€|\bEUR\b/iu.test(value) ? "EUR" : /\b(?:USD|CHF|GBP)\b/iu.exec(value)?.[0].toUpperCase() ?? "";
    return [{ role, label: AMOUNT_LABELS[role], value, currency, excerpt }];
  });
  // Multiple distinct values for one critical role need review instead of choosing the first.
  const one = (items: any[], role: string, issue: string) => {
    const found = items.filter(x => x.role === role);
    if (new Set(found.map(x => x.value)).size > 1) { issues.add(issue); return null; }
    return found[0] ?? null;
  };
  const due = one(dates, "due", "conflicting_dates"), expiry = one(dates, "expiry", "conflicting_dates");
  const issued = one(dates, "issue", "conflicting_dates");
  const dueAmount = one(amounts, "due", "conflicting_amounts");
  const amount = dueAmount ?? one(amounts, "total", "conflicting_amounts") ?? one(amounts, "salary", "conflicting_amounts");
  const actionQuote = completeActionQuote(source, quote(source, raw?.action?.excerpt));
  let status = actionQuote ? actionStatus(actionQuote) : "unknown";
  if (raw?.action?.status !== status && raw?.action?.status !== "required") status = "unknown";
  if (raw?.action?.status === "required" && status !== "required") issues.add("uncertain_action");
  if (dueAmount && /^0+(?:[.,]0+)?(?:\s*(?:EUR|€|USD|CHF|GBP))?$/i.test(comparable(dueAmount.value)) && /zahlen|überweisen|pay|ادفع|دفع/iu.test(actionQuote)) status = "none";
  if (options.extractionNote) issues.add("partial_ocr");
  if (options.truncated) issues.add("truncated_source");
  for (const issue of list(raw?.issues)) if (["multiple_documents", "unreadable_text", "contradictory_values"].includes(issue)) issues.add(issue);
  if (!source.trim()) issues.add("unreadable_text");
  const critical = ["partial_ocr", "truncated_source", "multiple_documents", "unreadable_text", "uncertain_date", "contradictory_values", "conflicting_dates", "conflicting_amounts"];
  const actionRequired = status === "required" && !critical.some(i => issues.has(i));
  if (actionQuote) evidence.push({ field: "action_text", value: status, excerpt: actionQuote });
  for (const [field, item] of [["issue_date", issued], ["due_date", due], ["expiry_date", expiry], ["amount_text", amount]] as const) {
    if (item) evidence.push({ field, value: item.value, excerpt: item.excerpt });
  }
  const notes: Record<string, string> = {
    uncertain_type: "نوع الورقة غير مؤكد.", unsupported_field: "استُبعدت معلومات لم أجد لها دليلاً في النص.",
    uncertain_date: "بعض التواريخ أو أدوارها غير مؤكدة؛ راجع الأصل.", uncertain_amount: "بعض المبالغ أو أدوارها غير مؤكدة.",
    uncertain_action: "الإجراء المطلوب يحتاج مراجعة؛ لم أعتمد إجراءً غير واضح.",
    conflicting_dates: "توجد تواريخ متعددة للدور نفسه؛ لم أختر موعداً تلقائياً.",
    conflicting_amounts: "توجد مبالغ متعارضة؛ راجع الأصل.", partial_ocr: "قراءة الورقة غير مكتملة؛ راجع الأصل قبل اعتماد المهام.",
    truncated_source: "حُلّل جزء من النص الطويل؛ قد توجد معلومات إضافية في بقية الورقة.",
    multiple_documents: "قد يحتوي الملف على أكثر من مستند؛ يلزم فصله أو مراجعته.",
    unreadable_text: "لم تتوفر قراءة واضحة وكافية للورقة.", contradictory_values: "توجد معلومات متعارضة تحتاج مراجعتك.",
  };
  const review = [...issues].map(i => notes[i]);
  const confidence = !source.trim() ? 0 : docType === "other" ? 0.25 : options.method === "rules" ? 0.5 : issues.size ? 0.6 : 0.85;
  const analysisStatus = !source.trim() ? "unreadable" : options.method === "rules" ? "limited" : issues.size ? "needs_review" : "analyzed";
  const reference = references.find(r => r?.kind === (docType === "invoice" ? "invoice" : docType === "contract" ? "contract" : "case")) ?? references[0];
  const smartTitle = [TYPES[docType], organization, reference?.value].filter(Boolean).join(" — ");
  const details = [TYPES[docType], organization ? `الجهة: ${organization}` : "", reference ? `الرقم: ${reference.value}` : "",
    ...amounts.slice(0, 4).map(a => `${a.label}: ${a.value}`), ...dates.slice(0, 5).map(d => `${d.label}: ${d.value}`),
    actionQuote ? `${status === "none" ? "لا يظهر إجراء مطلوب في هذا المقطع" : status === "possible" ? "إجراء اختياري بحسب النص" : status === "required" ? "المطلوب بحسب النص" : "مقطع يحتاج مراجعة الإجراء"}: ${actionQuote}` : "لم أتحقق من إجراء مطلوب.",
  ].filter(Boolean).join("\n");
  const prefix = options.method === "rules" ? "الفهم المتقدم غير متاح حالياً؛ هذه قراءة أولية تحتاج مراجعتك.\n" : "";
  return {
    ok: true, engine: "masahati-document-v3", model: options.model, schema_version: 3,
    analysis_status: analysisStatus, analysis_method: options.method, degraded_reason: options.failure ?? "",
    reply: prefix + details + (review.length ? "\n\n" + review.join("\n") : ""), summary: details.slice(0, 1200),
    classification: "document", labels: [TYPES[docType], ...(topic !== "other" ? [TOPICS[topic]] : [])],
    keywords: [TYPES[docType], TOPICS[topic], organization, ...references.map(r => r!.value)].filter(Boolean),
    confidence, actions: [], source_display_name: displayName,
    document: {
      schema_version: 3, analysis_status: analysisStatus, analysis_method: options.method,
      smart_title: docType === "other" ? displayName : smartTitle, doc_type: docType, doc_type_label: TYPES[docType],
      topic, topic_label: TOPICS[topic], language: ["ar", "de", "en", "mixed"].includes(raw?.language) ? raw.language : "unknown",
      organization, person_names: people, reference_number: reference?.value ?? "", references,
      amount_text: amount?.value ?? "", currency: amount?.currency ?? "", amounts,
      issue_date: issued?.value ?? "", due_date: due?.value ?? "", expiry_date: expiry?.value ?? "", dates,
      action_required: actionRequired, action_status: status, action_text: actionQuote,
      confidence, confidence_note: "تقدير احترازي لا يمثل نسبة دقة مقاسة.", evidence,
      needs_date_review: issues.has("uncertain_date") || issues.has("conflicting_dates"),
      review_reasons: review, issue_codes: [...issues],
    },
  };
}
