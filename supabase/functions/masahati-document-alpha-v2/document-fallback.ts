import { actionStatus, comparable, DATE_PATTERN, DATE_ROLES } from "./document-understanding.ts";

// Emergency reading only: explicit headings/labels, no inferred issuer or fabricated facts.
// Finding several keywords never bypasses the semantic model.
export function limitedReading(source: string) {
  const lines = source.split(/\n+|(?<=[.!?])\s+(?=[\p{L}])/u).map(s => s.trim()).filter(Boolean);
  const headings: [string, string, RegExp][] = [
    ["cancellation_confirmation", "other", /^(?:kündigungsbestätigung|bestätigung (?:ihrer |der )?kündigung|تأكيد (?:إلغاء|الإلغاء))/iu],
    ["cancellation_request", "other", /^(?:kündigung (?:meines|des)|طلب إلغاء)/iu],
    ["medical_transport", "health", /^(?:verordnung (?:einer |von )?krankenbeförderung|verordnung einer krankenfahrt|وصفة نقل طبي)/iu],
    ["payslip", "employment", /^(?:lohnabrechnung|gehaltsabrechnung|entgeltabrechnung|قسيمة راتب|كشف راتب)/iu],
    ["benefits_notice", "government", /^(?:bewilligungsbescheid|ablehnungsbescheid|wohngeldbescheid|قرار (?:إعانة|منح|رفض))/iu],
    ["tax_notice", "government", /^(?:einkommensteuerbescheid|steuerbescheid|قرار ضريبي)/iu],
    ["payment_reminder", "finance", /^(?:mahnung|zahlungserinnerung|مطالبة بالدفع|تذكير بالدفع)/iu],
    ["invoice", "finance", /^(?:rechnung\b|invoice\b|فاتورة)/iu],
    ["receipt", "finance", /^(?:quittung\b|zahlungsbestätigung|receipt\b|إيصال دفع)/iu],
    ["bank_statement", "finance", /^(?:kontoauszug|bank statement|كشف حساب)/iu],
    ["school", "education", /^(?:einladung zum elternabend|elternbrief|دعوة إلى اجتماع أولياء الأمور|رسالة مدرسية)/iu],
    ["appointment", "health", /^(?:terminbestätigung|appointment confirmation|تأكيد موعد)/iu],
    ["prescription", "health", /^(?:rezept\b|verordnung\b|وصفة طبية)/iu],
    ["contract", "housing", /^(?:mietvertrag\b|عقد إيجار|عقد ايجار)/iu],
    ["contract", "employment", /^(?:arbeitsvertrag\b|عقد عمل)/iu],
    ["contract", "other", /^(?:vertrag\b|contract\b|عقد\s)/iu],
    ["insurance", "insurance", /^(?:versicherungsschein|versicherungsbescheinigung|وثيقة تأمين)/iu],
    ["medical", "health", /^(?:arztbrief|befundbericht|تقرير طبي)/iu],
    ["official_notice", "government", /^(?:bescheid\b|قرار رسمي)/iu],
  ];
  let doc_type = "other", topic = "other", classification_excerpt = "";
  const detectedTypes = new Set<string>();
  for (const [type, subject, pattern] of headings) {
    const line = lines.slice(0, 12).find(s => pattern.test(s));
    if (line) {
      const heading = line.match(pattern)?.[0].trim();
      // A generic prefix of a specific heading (Verordnung...) or a reference
      // in the body (Arbeitsvertrag AV-123) is not another document heading.
      if (comparable(line.replace(/[.!?:]+$/u, "")) === comparable(heading ?? "")) detectedTypes.add(type);
      if (!classification_excerpt) { doc_type = type; topic = subject; classification_excerpt = line.slice(0, 500); }
    }
  }
  const dates: any[] = [], amounts: any[] = [], references: any[] = [];
  let organization: any = null;
  for (const line of lines) {
    const comparableLine = comparable(line);
    let previousEnd = 0;
    for (const date of comparableLine.matchAll(DATE_PATTERN)) {
      const before = comparableLine.slice(Math.max(previousEnd, date.index! - 100), date.index);
      previousEnd = date.index! + date[0].length;
      const role = Object.entries(DATE_ROLES).find(([, pattern]) => pattern.test(before))?.[0];
      if (role) dates.push({ role, value: date[0], excerpt: line.slice(0, 500) });
    }
    for (const [role, pattern] of [
      ["due", /(?:offener betrag|restbetrag|zu zahlen|amount due|المتبقي|المستحق)\s*[:=-]?\s*([\d٠-٩۰-۹][\d٠-٩۰-۹.,٬٫ ]*\s*(?:EUR|€|USD|CHF|GBP))/giu],
      ["total", /(?:gesamtbetrag|rechnungsbetrag|total|الإجمالي|الاجمالي|المجموع)\s*[:=-]?\s*([\d٠-٩۰-۹][\d٠-٩۰-۹.,٬٫ ]*\s*(?:EUR|€|USD|CHF|GBP))/giu],
      ["paid", /(?:bereits bezahlt|bezahlt|paid|المدفوع)\s*[:=-]?\s*([\d٠-٩۰-۹][\d٠-٩۰-۹.,٬٫ ]*\s*(?:EUR|€|USD|CHF|GBP))/giu],
      ["salary", /(?:netto|auszahlungsbetrag|صافي الراتب)\s*[:=-]?\s*([\d٠-٩۰-۹][\d٠-٩۰-۹.,٬٫ ]*\s*(?:EUR|€|USD|CHF|GBP))/giu],
    ] as [string, RegExp][]) {
      for (const match of line.matchAll(pattern)) amounts.push({ role, value: match[1].trim(), excerpt: match[0] });
    }
    for (const [kind, pattern] of [
      ["invoice", /(?:rechnungsnummer|invoice number|رقم الفاتورة)\s*[:#-]?\s*([\p{L}\p{N}][\p{L}\p{N}._/-]{2,})/giu],
      ["contract", /(?:vertragsnummer|contract number|رقم العقد)\s*[:#-]?\s*([\p{L}\p{N}][\p{L}\p{N}._/-]{2,})/giu],
      ["case", /(?:aktenzeichen|reference|رقم الملف|الرقم المرجعي)\s*[:#-]?\s*([\p{L}\p{N}][\p{L}\p{N}._/-]{2,})/giu],
      ["customer", /(?:kundennummer|رقم العميل)\s*[:#-]?\s*([\p{L}\p{N}][\p{L}\p{N}._/-]{2,})/giu],
    ] as [string, RegExp][]) {
      for (const match of line.matchAll(pattern)) references.push({ kind, value: match[1].replace(/[.,;:]+$/g, ""), excerpt: match[0] });
    }
    const org = line.match(/^(?:Vermieter|Aussteller|Behörde|Versicherer|Firma|Company|Issuer|الجهة|الشركة)\s*:\s*([^.;]{3,100})/iu);
    if (org && !organization) organization = { value: org[1].trim(), excerpt: org[0] };
  }
  const actionLine = lines.find(s => actionStatus(s) === "required") ?? lines.find(s => ["none", "possible"].includes(actionStatus(s)));
  return {
    doc_type, topic, classification_excerpt, organization, references, amounts, dates, person_names: [],
    issues: detectedTypes.size > 1 ? ["multiple_documents"] : [],
    action: actionLine ? { status: actionStatus(actionLine), excerpt: actionLine.slice(0, 500) } : null,
    language: /[\u0600-\u06ff]/u.test(source) ? "ar" : /rechnung|vertrag|bescheid|termin|verordnung/i.test(source) ? "de" : "unknown",
  };
}
