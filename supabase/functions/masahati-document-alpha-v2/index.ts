import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { normalizedDate, validateResultDates } from "./document-dates.ts";

const PUBLISHABLE_KEY = "sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3";
const FALLBACK = "https://hxrvlvqlkfylbjicdfzs.supabase.co/functions/v1/masahati-document-alpha";
const HEADERS = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
};

const clip = (v: unknown, n: number) => typeof v === "string" ? v.trim().slice(0, n) : "";
const out = (body: unknown, status=200) => new Response(JSON.stringify(body), {status, headers: HEADERS});

function isoDate(raw: string) {
  return normalizedDate(raw);
}

function findLabeled(text: string, labels: string[], valueSource: string) {
  for (const label of labels) {
    const esc = label.replace(/[.*+?^$()|[\]{}\\]/g, "\\$&");
    const re = new RegExp(esc + "\\s*[:#-]?\\s*(" + valueSource + ")", "i");
    const m = text.match(re);
    if (m?.[1]) {
      const idx = m.index ?? 0;
      return {
        value: m[1].trim(),
        excerpt: text.slice(Math.max(0, idx-40), Math.min(text.length, idx+m[0].length+80)).trim()
      };
    }
  }
  return null;
}

function deterministic(displayName: string, ocr: string) {
  const text = ocr.replace(/\s+/g, " ").trim();
  const lower = text.toLowerCase();

  let docType = "other";
  let typeLabel = "مستند";
  if (/mietvertrag|arbeitsvertrag|kaufvertrag|\bvertrag\b|\bcontract\b|عقد/.test(lower)) { docType="contract"; typeLabel="عقد"; }
  else if (/rechnung|invoice|فاتورة/.test(lower)) { docType="invoice"; typeLabel="فاتورة"; }
  else if (/bescheid|bewilligung|ablehnung|verwaltungsakt|قرار|إشعار رسمي/.test(lower)) { docType="official_notice"; typeLabel="قرار رسمي"; }
  else if (/versicherung|krankenkasse|policy|تأمين/.test(lower)) { docType="insurance"; typeLabel="تأمين"; }
  else if (/arzt|krankenhaus|patient|diagnose|medical|طبي|مريض/.test(lower)) { docType="medical"; typeLabel="مستند طبي"; }
  else if (/schule|kindergarten|zeugnis|school|مدرسة/.test(lower)) { docType="school"; typeLabel="مدرسة"; }
  else if (/garantie|gewährleistung|warranty|ضمان/.test(lower)) { docType="warranty"; typeLabel="ضمان"; }
  else if (/bank|iban|konto|bank statement|كشف حساب/.test(lower)) { docType="bank"; typeLabel="بنك"; }

  const datePattern = "(?:0?[1-9]|[12]\\d|3[01])[.\\/-](?:0?[1-9]|1[0-2])[.\\/-](?:19|20)\\d{2}";
  const refRaw = findLabeled(text,
    ["Vertragsnummer","Aktenzeichen","Kundennummer","Rechnungsnummer","Versicherungsnummer","Referenz","Reference","Contract number","رقم العقد","رقم الملف","الرقم المرجعي"],
    "[A-Z0-9][A-Z0-9._\\/-]{2,}");
  const ref = refRaw ? { ...refRaw, value: refRaw.value.replace(/[.,;:]+$/g,"") } : null;
  const expiry = findLabeled(text,
    ["Vertragsende","Vertragsablauf","Gültig bis","Gueltig bis","Ablaufdatum","Expiry date","Expires","Valid until","تاريخ الانتهاء","صالح حتى"],
    datePattern);
  const due = findLabeled(text,
    ["Fällig am","Faellig am","Zahlbar bis","Frist bis","Kündigung bis","Kuendigung bis","spätestens bis","spaetestens bis","Due date","Deadline","Pay by","آخر موعد","يجب قبل"],
    datePattern);
  const issue = findLabeled(text,
    ["Ausstellungsdatum","Ausgestellt am","Rechnungsdatum","Bescheiddatum","Issue date","Issued on","تاريخ الإصدار"],
    datePattern);

  const expiryDate = expiry ? isoDate(expiry.value) : "";
  const dueDate = due ? isoDate(due.value) : "";
  const issueDate = issue ? isoDate(issue.value) : "";
  const needsDateReview = !!((expiry && !expiryDate) || (due && !dueDate) || (issue && !issueDate));

  const amountMatch = text.match(/(?:^|\s)(\d{1,3}(?:[.\s]\d{3})*(?:,\d{2})?|\d+(?:[.,]\d{2})?)\s*(EUR|€|USD|CHF)\b/i);
  const amountText = amountMatch ? amountMatch[0].trim() : "";
  const currency = /€|EUR/i.test(amountText) ? "EUR" : /USD/i.test(amountText) ? "USD" : /CHF/i.test(amountText) ? "CHF" : "";

  const org = text.match(/(?:Vermieter|Aussteller|Behörde|Behoerde|Versicherer|Firma|Company|Issuer|الجهة|الشركة)\s*[:#-]?\s*([^.;]{3,100})/i)?.[1]?.trim() ?? "";
  const people = [...text.matchAll(/(?:Mieter|Patient|Versicherte(?:r| Person)?|Tenant|Patient name|الاسم|المستأجر)\s*[:#-]?\s*([A-ZÄÖÜ][A-Za-zÄÖÜäöüß' -]{3,80})/g)]
    .map(m => m[1].trim()).filter((v,i,a)=>a.indexOf(v)===i).slice(0,6);

  const actionSource = text.replace(
    /(?:0?[1-9]|[12]\d|3[01])[.](?:0?[1-9]|1[0-2])[.](?:19|20)\d{2}/g,
    value => value.replaceAll(".","-")
  );
  const actionMatch = actionSource.match(
    /(?:^|[.!?]\s*)([^.!?]{0,240}(?:(?:\b(?:muss|müssen|muß|bitte|spätestens|spaetestens|kündigen|kuendigen|einreichen|zahlen|überweisen|ueberweisen|must|submit|pay)\b)|(?:يجب|يرجى|ادفع|أرسل))[^.!?]{0,240}[.!?]?)/i
  );
  const action = (actionMatch?.[1]?.trim() ?? "")
    .replace(/(\d{1,2})-(\d{1,2})-(\d{4})/g, "$1.$2.$3");
  const actionRequired = action.length >= 8;

  const evidence: any[] = [];
  if (ref) evidence.push({field:"reference_number", value:ref.value, excerpt:ref.excerpt});
  if (expiryDate && expiry) evidence.push({field:"expiry_date", value:expiryDate, excerpt:expiry.excerpt});
  if (dueDate && due) evidence.push({field:"due_date", value:dueDate, excerpt:due.excerpt});
  if (issueDate && issue) evidence.push({field:"issue_date", value:issueDate, excerpt:issue.excerpt});
  if (amountText) evidence.push({field:"amount_text", value:amountText, excerpt:amountText});
  if (actionRequired) evidence.push({field:"action_text", value:action, excerpt:action});

  const supported = [docType!=="other", !!ref, !!expiryDate, !!dueDate, !!issueDate, !!amountText, actionRequired].filter(Boolean).length;
  const confidence = supported >= 3 ? 0.97 : supported === 2 ? 0.91 : supported === 1 ? 0.82 : 0.45;
  const smartTitle = [typeLabel, org, ref?.value].filter(Boolean).join(" — ") || displayName || typeLabel;
  const important = [
    ref?.value ? "الرقم: " + ref.value : "",
    expiryDate ? "ينتهي: " + expiryDate : "",
    dueDate ? "آخر موعد: " + dueDate : "",
    amountText ? "المبلغ: " + amountText : "",
    actionRequired ? "المطلوب: " + action : "",
  ].filter(Boolean).join("، ");

  return {
    ok: true,
    engine: "masahati-document-alpha-v2",
    model: "deterministic+fallback",
    reply: needsDateReview ? "وجدت تاريخاً غير صالح في القراءة النصية، فلم أعتمده. راجع التاريخ في الملف الأصلي." : important ? typeLabel + ": " + important : "قرأت المستند بدون إضافة معلومات غير موجودة.",
    classification: "document",
    labels: [typeLabel, org].filter(Boolean),
    keywords: [docType, ref?.value, org, expiryDate, dueDate].filter(Boolean),
    summary: important ? typeLabel + ". " + important : text.slice(0,700),
    confidence,
    actions: [],
    document: {
      smart_title: smartTitle,
      doc_type: docType,
      organization: org,
      person_names: people,
      reference_number: ref?.value ?? "",
      amount_text: amountText,
      currency,
      issue_date: issueDate,
      due_date: dueDate,
      expiry_date: expiryDate,
      action_required: actionRequired,
      action_text: action,
      confidence,
      evidence,
      needs_date_review: needsDateReview,
    },
    source_display_name: displayName,
  };
}

function merge(base: any, det: any) {
  if (!base?.ok) return det;
  const bd = base.document && typeof base.document === "object" ? base.document : {};
  const dd = det.document;
  const doc: any = {...bd};
  for (const k of ["smart_title","doc_type","organization","reference_number","amount_text","currency","issue_date","due_date","expiry_date","action_text"]) {
    if ((!doc[k] || String(doc[k]).trim()==="") && dd[k]) doc[k]=dd[k];
  }
  if ((!Array.isArray(doc.person_names) || doc.person_names.length===0) && dd.person_names.length) doc.person_names=dd.person_names;
  if ((!Array.isArray(doc.evidence) || doc.evidence.length===0) && dd.evidence.length) doc.evidence=dd.evidence;
  if (dd.action_required) doc.action_required=true;
  doc.confidence=Math.max(Number(doc.confidence)||0, Number(dd.confidence)||0);
  return {
    ...base,
    engine:"masahati-document-alpha-v2",
    reply: clip(base.reply,1600) || det.reply,
    summary: clip(base.summary,900) || det.summary,
    labels:Array.from(new Set([...(Array.isArray(base.labels)?base.labels:[]), ...det.labels])),
    keywords:Array.from(new Set([...(Array.isArray(base.keywords)?base.keywords:[]), ...det.keywords])),
    confidence:Math.max(Number(base.confidence)||0, Number(det.confidence)||0),
    document:doc,
  };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", {headers:{...HEADERS,"Access-Control-Allow-Headers":"apikey, content-type"}});
  if (req.method !== "POST") return out({ok:false,error:"method_not_allowed"},405);
  if ((req.headers.get("apikey") ?? "") !== PUBLISHABLE_KEY) return out({ok:false,error:"unauthorized"},401);

  let body: any;
  try { body=await req.json(); } catch { return out({ok:false,error:"invalid_json"},400); }

  const displayName=clip(body?.displayName,180);
  const ocr=clip(body?.ocrText,14000);
  if (!displayName && !ocr) return out({ok:false,error:"empty_document"},400);
  if (!ocr) {
    return out({
      ok:true,engine:"masahati-document-alpha-v2",model:"none",
      reply:"حفظت الملف، لكن OCR لم يستخرج نصاً واضحاً لذلك لن أخمّن محتواه.",
      classification:"document",labels:["مستند"],keywords:[],
      summary:"مستند محفوظ دون نص OCR مقروء.",confidence:0.99,actions:[],
      document:{smart_title:displayName,doc_type:"",organization:"",person_names:[],reference_number:"",amount_text:"",currency:"",issue_date:"",due_date:"",expiry_date:"",action_required:false,action_text:"",confidence:0.99,evidence:[]},
      source_display_name:displayName
    });
  }

  const det=deterministic(displayName,ocr);
  // An invalid labelled date needs source review; a model must not guess a replacement.
  if (det.confidence >= 0.90 || det.document.needs_date_review) return out(det);

  try {
    const r=await fetch(FALLBACK,{
      method:"POST",
      headers:{"Content-Type":"application/json","Accept":"application/json","apikey":PUBLISHABLE_KEY},
      body:JSON.stringify(body),
      signal:AbortSignal.timeout(28000)
    });
    const fallback=await r.json().catch(()=>null);
    return out(validateResultDates(merge(fallback,det)));
  } catch {
    return out(det);
  }
});
