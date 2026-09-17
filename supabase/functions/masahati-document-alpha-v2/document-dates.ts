const DATE_FIELDS = ["issue_date", "due_date", "expiry_date"] as const;

export function normalizedDate(value: unknown): string {
  if (typeof value !== "string") return "";
  const raw = value.trim();
  const iso = raw.match(/^((?:19|20)\d{2})-(\d{2})-(\d{2})$/);
  const local = raw.match(/^(0?[1-9]|[12]\d|3[01])[./-](0?[1-9]|1[0-2])[./-]((?:19|20)\d{2})$/);
  if (!iso && !local) return "";
  const year = Number(iso ? iso[1] : local![3]);
  const month = Number(iso ? iso[2] : local![2]);
  const day = Number(iso ? iso[3] : local![1]);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return "";
  return date.toISOString().slice(0, 10);
}

/** Apply the same calendar validation to upstream model output as to OCR rules. */
export function validateResultDates(result: any): any {
  if (!result?.document || typeof result.document !== "object") return result;
  const document = { ...result.document };
  const invalid = new Set<string>();
  for (const field of DATE_FIELDS) {
    const raw = document[field];
    const normalized = normalizedDate(raw);
    if (raw != null && String(raw).trim() !== "" && !normalized) invalid.add(field);
    document[field] = normalized;
  }
  if (!invalid.size) return { ...result, document };
  document.evidence = (Array.isArray(document.evidence) ? document.evidence : [])
    .filter((item: any) => !invalid.has(item?.field));
  document.needs_date_review = true;
  return {
    ...result,
    document,
    reply: "قرأت المستند، لكن بعض التواريخ المستخرجة غير صالحة. لم أعتمدها؛ راجع التاريخ في الملف الأصلي.",
    summary: "المستند يحتاج مراجعة التواريخ في الأصل؛ لم تُعتمد التواريخ غير الصالحة.",
  };
}
