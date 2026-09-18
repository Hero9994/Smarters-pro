import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { buildConversation, explicitAction, supportedActions, documentDateAnswer, parseModelEnvelope } from "./conversation.ts";
import { providerConfig } from "./provider.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const PUBLISHABLE_KEY = "sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const admin = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
const headers = { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" };
const out = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers });
const clip = (v: unknown, n: number) => typeof v === "string" ? v.trim().slice(0, n) : "";
async function hash(s: string) { const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)); return [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join(""); }
function cleanSearchQuery(text: string) {
  const trimmed = text.trim();
  const first = trimmed
    .replace(/^(?:وين|أين|اين|ابحث(?:لي)?(?: عن)?|دور(?:لي)?(?: على)?|فتش(?:لي)?(?: عن)?|find|search for|suche nach|wo ist)(?:\s+|$)/i, "")
    .trim();
  const second = first
    .replace(/^(?:حطيت|حطيتلي|حفظت|خزنت|وضعت|حاطط|حافظة)(?:\s+|$)/i, "")
    .replace(/[؟?]+$/g, "")
    .trim();
  return second || trimmed;
}
const allowedClasses = new Set(["note","task","reminder","work_schedule","document","idea","personal","search","command","other"]);
const allowedActions = new Set(["create_reminder","enrich_previous_document","search","archive_space","pin_space","rename_space","move_last_item","create_space","rename_last_document","move_last_document"]);
function stringArray(v: any, max: number, len: number) { return Array.isArray(v) ? v.map((x: any) => clip(x, len)).filter(Boolean).slice(0, max) : []; }
function normalize(p: any, text: string) {
  const classification = allowedClasses.has(clip(p?.classification, 30)) ? clip(p.classification, 30) : "other";
  const actions = Array.isArray(p?.actions) ? p.actions.map((a: any) => ({
    type: clip(a?.type, 40),
    args: a?.args && typeof a.args === "object" ? a.args : {},
    requires_confirmation: a?.requires_confirmation !== false,
  })).filter((a: any) => allowedActions.has(a.type)).slice(0, 4) : [];
  for (const action of actions) if (action.type === "search") action.args.query = cleanSearchQuery(clip(action.args?.query, 180) || text);
  const summary = clip(p?.summary, 600) || text.slice(0, 260);
  let reply = clip(p?.reply, 1600) || "فهمت المحتوى وحفظته.";
  // Execution is reported by Android after a tool actually succeeds. Never let the model falsely claim success.
  if (/(تم\s+(إنشاء|انشاء|إضافة|اضافة|أرشفة|ارشفة|نقل|تغيير|تثبيت)|أنشأت|انشأت|أضفت|اضفت|نقلت|أرشفت|ارشفت|ثبتت|غيّرت)/i.test(reply) && actions.length > 0) {
    reply = "فهمت طلبك وسأنفذه داخل التطبيق إذا كانت التفاصيل كافية.";
  }
  const c = Number(p?.confidence);
  return {
    reply,
    classification,
    labels: stringArray(p?.labels, 10, 70),
    keywords: stringArray(p?.keywords, 14, 90),
    summary,
    confidence: Number.isFinite(c) ? Math.max(0, Math.min(1, c)) : 0.72,
    actions,
  };
}

function buildContext(recent: any[]) {
  return recent.slice(-20).map((m: any, i: number) => {
    const role = m?.role === "assistant" ? "assistant" : "user";
    const kind = clip(m?.kind, 30);
    const displayName = clip(m?.displayName, 180);
    const classification = clip(m?.classification, 80);
    const tags = clip(m?.tags, 320);
    const summary = clip(m?.summary, 700);
    const text = clip(m?.text, 1400);
    const ocrText = clip(m?.ocrText, 3400);
    const content = [
      kind ? "kind: " + kind : "",
      displayName ? "name: " + displayName : "",
      classification ? "classification: " + classification : "",
      tags ? "tags: " + tags : "",
      summary ? "summary: " + summary : "",
      text ? "text: " + text : "",
      ocrText ? "ocr: " + ocrText : "",
    ].filter(Boolean).join("\n");
    return { id: "R" + (i + 1), role, kind, displayName, classification, tags, summary, text, ocrText, content };
  }).filter((x: any) => x.content);
}

async function askModel(provider: ReturnType<typeof providerConfig>, timeoutMs: number, messages: any[]) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const r = await fetch(provider.url, {
      method: "POST",
      headers: provider.headers,
      body: JSON.stringify({ model:provider.model, messages, temperature: 0.05, max_tokens: 1050, stream: false }),
      signal: controller.signal,
    });
    const raw = await r.text();
    if (!r.ok) return null;
    let env: any; try { env = JSON.parse(raw); } catch { return null; }
    return parseModelEnvelope(env);
  } catch { return null; } finally { clearTimeout(timer); }
}
function unavailable(text: string) {
  return {ok:true, engine:"edge-fallback-v12", model:"rules-v12",
    reply:"تعذر الحصول على جواب من المساعد الآن. بقيت رسالتك محفوظة، ويمكنك إعادة المحاولة.",
    classification:"other", labels:[], keywords:[], summary:text.slice(0,320), confidence:0, actions:[]};
}
function extractTime(s: string) {
  const normalized = s.replace(/[٠-٩]/g, d => "٠١٢٣٤٥٦٧٨٩".indexOf(d).toString());
  const exact = normalized.match(/(?:[01]?\d|2[0-3])[:.]\d{2}/)?.[0];
  if (exact) return exact.replace(".", ":");
  const clock = normalized.match(/(?:الساعة|الساعه|at|um)\s*(\d{1,2})(?:\s*(ص|م|صباح|مساء|am|pm))?/i);
  if (!clock) return null;
  let h = Number(clock[1]);
  const period = String(clock[2] || "").toLowerCase();
  if ((period === "م" || period.includes("مساء") || period === "pm") && h >= 1 && h <= 11) h += 12;
  if ((period === "ص" || period.includes("صباح") || period === "am") && h === 12) h = 0;
  return h >= 0 && h <= 23 ? String(h).padStart(2,"0")+":00" : null;
}
function findDateNearLabel(text: string, labels: string[]) {
  const dateRegex = /(?:0?[1-9]|[12]\d|3[01])[.\/-](?:0?[1-9]|1[0-2])[.\/-](?:19|20)\d{2}/g;
  const lower = text.toLowerCase();
  for (const label of labels) {
    let from = 0;
    while (true) {
      const index = lower.indexOf(label.toLowerCase(), from);
      if (index < 0) break;
      const after = text.slice(index + label.length, Math.min(text.length, index + label.length + 100));
      const afterDate = after.match(dateRegex)?.[0];
      if (afterDate) return afterDate;
      const before = text.slice(Math.max(0, index - 60), index);
      const beforeDates = before.match(dateRegex) || [];
      if (beforeDates.length) return beforeDates[beforeDates.length - 1];
      from = index + label.length;
    }
  }
  return null;
}

function localFallback(text: string, spaceTitle: string, recent: any[], nowRaw: string) {
  const lower = text.toLowerCase();
  const context = recent.map((m: any) => String(m.content || "")).join(" ");
  const has = (...w: string[]) => w.some(x => lower.includes(x.toLowerCase()));
  const time = extractTime(text) ?? extractTime(context);
  const days = ["الاثنين","الإثنين","اثنين","اتنين","الثلاثاء","ثلاثاء","الأربعاء","الاربعاء","أربعاء","اربعاء","الخميس","خميس","الجمعة","جمعة","السبت","سبت","الأحد","الاحد","أحد","احد","Montag","Dienstag","Mittwoch","Donnerstag","Freitag","Samstag","Sonntag"];
  const day = days.find(d => lower.includes(d.toLowerCase())) ?? days.find(d => context.toLowerCase().includes(d.toLowerCase()));
  const keywords: string[] = [];
  if (time) keywords.push(time);
  if (day) keywords.push(day);

  const hasRecentDocument = recent.some((x: any) =>
    x?.kind === "file" || /kind: file|ocr:|pdf|vertrag|bescheid|rechnung|وثيقة|مستند|عقد|فاتورة/i.test(String(x?.content || ""))
  );
  const latestDocContext = [...recent].reverse().map((x: any) => String(x?.content || "")).find((v: string) =>
    /kind: file|ocr:|pdf|vertrag|bescheid|rechnung|وثيقة|مستند|عقد|فاتورة/i.test(v)
  ) || "";

  const explicitSearch = /^(?:وين|أين|اين|ابحث(?:لي)?(?: عن)?|دور(?:لي)?(?: على)?|فتش(?:لي)?(?: عن)?|find(?: me)?|search(?: for)?|suche(?: nach)?|wo ist)(?:\s+|$)/i.test(text.trim());
  if (explicitSearch) {
    const q = cleanSearchQuery(text);
    return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:"سأبحث داخل مساحاتك عن «"+q+"».",classification:"search",labels:["بحث"],keywords:[q],summary:text.slice(0,320),confidence:0.98,actions:[{type:"search",args:{query:q},requires_confirmation:false}]};
  }

  const asksOpponent = /^(?:طيب\s*)?(?:ضد\s+(?:مين|من)|مين\s+الخصم|من\s+الخصم|gegen\s+wen)\s*[؟?]?$/i.test(text.trim());
  if (asksOpponent && recent.length) {
    const source = [...recent].reverse()
      .map((x: any) => String(x?.text || ""))
      .find((v: string) => /(?:ضد|gegen)\s+/i.test(v));
    const match = source?.match(/(?:ضد|gegen)\s+([^،,.!؟?\n]{2,90})/i);
    let opponent = String(match?.[1] || "").trim();
    opponent = opponent
      .replace(/\s+(?:يوم|الساعة|الأحد|الاحد|الاثنين|الإثنين|الثلاثاء|الأربعاء|الاربعاء|الخميس|الجمعة|السبت)\b.*$/i, "")
      .trim();
    if (opponent) {
      return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"حسب آخر معلومة عندي: المباراة ضد "+opponent+".",classification:"note",labels:["مباراة"],keywords:[opponent],summary:text.slice(0,320),confidence:0.97,actions:[]};
    }
    return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"ما لقيت اسم الخصم بشكل واضح في آخر معلومات المباراة.",classification:"note",labels:["مباراة"],keywords:[],summary:text.slice(0,320),confidence:0.8,actions:[]};
  }

  const dates = latestDocContext.match(/(?:0?[1-9]|[12]\d|3[01])[.\/-](?:0?[1-9]|1[0-2])[.\/-](?:19|20)\d{2}/g) || [];
  const asksStartDate = has("متى بدأ","متى بدا","بداية","يبدأ","يبدا","beginn","startdatum","vertragsbeginn","gültig ab","gueltig ab");
  const asksEndDate = has("انتهاء","ينتهي","بينتهي","تنتهي","نهاية","ende","ablauf","gültig bis","gueltig bis","vertragsende");
  const asksAnyDate = has("متى","تاريخ","datum","date");
  if (hasRecentDocument && (asksStartDate || asksEndDate || asksAnyDate) && dates.length) {
    const uniqueDates = [...new Set(dates)].slice(0,6);
    if (asksStartDate) {
      const d = findDateNearLabel(latestDocContext, ["vertragsbeginn","beginn","startdatum","gültig ab","gueltig ab","بداية","يبدأ","يبدا"])
        || (uniqueDates.length === 1 ? uniqueDates[0] : null);
      if (d) return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"تاريخ البداية الظاهر في المستند هو "+d+".",classification:"document",labels:["مستند"],keywords:[d],summary:latestDocContext.slice(0,420),confidence:0.93,actions:[]};
      return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"في المستند أكثر من تاريخ، لكن ما في تسمية واضحة تحدد أي واحد هو تاريخ البداية. ما رح أخمّن.",classification:"document",labels:["مستند"],keywords:uniqueDates,summary:latestDocContext.slice(0,420),confidence:0.9,actions:[]};
    }
    if (asksEndDate) {
      const d = findDateNearLabel(latestDocContext, ["vertragsende","gültig bis","gueltig bis","ablauf","endet","ende","انتهاء","ينتهي","تنتهي"])
        || (uniqueDates.length === 1 ? uniqueDates[0] : null);
      if (d) return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"تاريخ الانتهاء الظاهر في المستند هو "+d+".",classification:"document",labels:["مستند"],keywords:[d],summary:latestDocContext.slice(0,420),confidence:0.93,actions:[]};
      return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"في المستند أكثر من تاريخ، لكن ما في تسمية واضحة تحدد أي واحد هو تاريخ الانتهاء. ما رح أخمّن.",classification:"document",labels:["مستند"],keywords:uniqueDates,summary:latestDocContext.slice(0,420),confidence:0.9,actions:[]};
    }
    if (uniqueDates.length === 1) {
      const d = uniqueDates[0];
      return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"التاريخ الظاهر في المستند هو "+d+".",classification:"document",labels:["مستند"],keywords:[d],summary:latestDocContext.slice(0,420),confidence:0.88,actions:[]};
    }
    return {ok:true,engine:"edge-fallback-v8",model:"rules-v8",reply:"في المستند أكثر من تاريخ: "+uniqueDates.join("، ")+". أي تاريخ تقصد؟",classification:"document",labels:["مستند"],keywords:uniqueDates,summary:latestDocContext.slice(0,420),confidence:0.9,actions:[]};
  }

  if (hasRecentDocument && has("شو فيها","شو فيه","شو مكتوب","هاد شو","هاي شو","ما هذا","ما هذه","was ist","worum geht")) {
    const summaryMatch = latestDocContext.match(/summary:\s*([^\n]+)/i);
    const ocrMatch = latestDocContext.match(/ocr:\s*([^\n]{20,500})/i);
    const answer = (summaryMatch?.[1] || ocrMatch?.[1] || latestDocContext).trim().slice(0,420);
    return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:answer,classification:"document",labels:["مستند"],keywords:[],summary:answer,confidence:0.72,actions:[]};
  }

  const describesPrevious = /^(?:هاي|هذه|هي|هاد|هذا)\s+(?:ورقة|الورقة|مستند|المستند|عقد|العقد|وثيقة|الوثيقة)(?:\s|$)/i.test(text.trim());
  const correctsPrevious = /(?:^|[،,]\s*)(?:لا\s+)?(?:مو|مش|ليس)\s+(?:جدول\s+)?دوام/i.test(text) && /(ورقة|مستند|وثيقة|موافقة|تصريح|نقل|طبيب|arzt|transport)/i.test(text);
  if (hasRecentDocument && (describesPrevious || correctsPrevious) && !/[؟?]\s*$/.test(text.trim())) {
    const medicalTransport = /(نقل|موافقة|تصريح).*(طبيب|دكتور)|krankenbeförder|krankentransport|\bArzt\b/i.test(text+" "+latestDocContext);
    const cleaned = text.replace(/^(?:لا\s+)?(?:مو|مش|ليس)\s+(?:جدول\s+)?دوام\s*[,،:-]*\s*/i,"").replace(/^(?:هاي|هذه|هي|هاد|هذا)\s*/i,"").trim();
    const summary = medicalTransport ? "موافقة/تصريح لنقل المريض من المنزل إلى الطبيب" : cleaned.slice(0,500);
    const labels = medicalTransport ? ["مستند","نقل مرضى","طبيب"] : ["مستند"];
    const terms = cleaned.split(/[^\p{L}\p{N}]+/u).map(x => x.trim()).filter(x => x.length >= 3).slice(0,10);
    if (medicalTransport) terms.push("Krankenbeförderung","Arzt","Wohnung");
    const keywordList = [...new Set(terms)].slice(0,12);
    const reply = correctsPrevious ? "فهمت التصحيح: المستند السابق ليس جدول دوام؛ هو "+summary+"، وربطت الوصف الصحيح بالملف." : "ربطت وصفك بالمستند السابق: "+summary+".";
    return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply,classification:"document",labels,keywords:keywordList,summary,confidence:0.96,actions:[{type:"enrich_previous_document",args:{summary,labels,keywords:keywordList},requires_confirmation:false}]};
  }

  const createSpaceMatch = text.match(/(?:اعمل|أعمل|انشئ|أنشئ|سوي|سوّي|create)\s*(?:لي)?\s*(?:مساحة|space)(?:\s+جديدة)?(?:\s+(?:اسمها|باسم|called))?\s*[«"']?([^»"']+)?/i);
  if (createSpaceMatch) {
    const name = String(createSpaceMatch[1] || "").trim().replace(/[؟?]+$/g,"").slice(0,80);
    return name
      ? {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:"فهمت: إنشاء مساحة جديدة باسم «"+name+"».",classification:"command",labels:["مساحة"],keywords:[name],summary:text.slice(0,320),confidence:0.98,actions:[{type:"create_space",args:{name},requires_confirmation:false}]}
      : {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:"فهمت أنك تريد مساحة جديدة. ما الاسم الذي تريده لها؟",classification:"command",labels:["مساحة"],keywords:[],summary:text.slice(0,320),confidence:0.85,actions:[]};
  }

  if (/^(?:فكرة|اقتراح)(?:\s+للتطبيق|\s+للمشروع)?\s*[:：-]?/i.test(text.trim()) || /\bidea\b/i.test(text)) {
    const idea = text.replace(/^(?:فكرة|اقتراح)(?:\s+للتطبيق|\s+للمشروع)?\s*[:：-]?\s*/i,"").trim();
    const terms = idea.split(/[^\p{L}\p{N}]+/u).filter(x=>x.length>=3).slice(0,8);
    return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:"فهمت الفكرة: "+idea.slice(0,260)+". صنفتها كفكرة مشروع حتى لا تختلط بالتذكيرات.",classification:"idea",labels:["فكرة"],keywords:terms,summary:idea.slice(0,420),confidence:0.94,actions:[]};
  }

  if (/(مباراة|ماتش|تدريب|بطولة|spiel|training|turnier)/i.test(text)) {
    const eventType = /(تدريب|training)/i.test(text) ? "تدريب" : "مباراة";
    const details = [day, time ? "الساعة "+time : ""].filter(Boolean).join(" ");
    const terms = text.split(/[^\p{L}\p{N}:.]+/u).filter(x=>x.length>=2).slice(0,10);
    return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:details ? "فهمت "+eventType+": "+details+". حفظت التفاصيل والأسماء المهمة للبحث." : "فهمت أنها معلومة "+eventType+" وحفظت تفاصيلها للبحث.",classification:"note",labels:[eventType,...(day?[day]:[])],keywords:terms,summary:text.slice(0,420),confidence:0.9,actions:[]};
  }

  if (/(موعد|termin|طبيب|دكتور|zahnarzt|أسنان|اسنان)/i.test(text) && !/(مستند|ورقة|فاتورة|عقد)/i.test(text)) {
    const details = [day, time ? "الساعة "+time : ""].filter(Boolean).join(" ");
    const terms = text.split(/[^\p{L}\p{N}:.]+/u).filter(x=>x.length>=2).slice(0,10);
    return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:details ? "فهمت الموعد: "+details+". حفظت نوع الموعد والتفاصيل للبحث." : "فهمت أنها معلومة موعد وحفظت تفاصيلها للبحث.",classification:"note",labels:["موعد",...(/(طبيب|دكتور|arzt|zahnarzt|أسنان|اسنان)/i.test(text)?["طبيب"]:[]),...(day?[day]:[])],keywords:terms,summary:text.slice(0,420),confidence:0.9,actions:[]};
  }

  if (/^(?:لازم|ضروري|مهمة|task|todo)(?:\s|:|$)/i.test(text.trim())) {
    const task = text.replace(/^(?:لازم|ضروري|مهمة|task|todo)\s*/i,"").trim();
    const terms = task.split(/[^\p{L}\p{N}:.]+/u).filter(x=>x.length>=2).slice(0,10);
    return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:"فهمت أنها مهمة: "+task.slice(0,260)+". صنفتها كمهمة حتى لا تضيع بين الملاحظات.",classification:"task",labels:["مهمة",...(/(تأمين|التامين|التأمين|versicherung)/i.test(text)?["تأمين"]:[])],keywords:terms,summary:task.slice(0,420),confidence:0.92,actions:[]};
  }

  if (/(ركنت|صفنت|موقف السيارة|السيارة بالطابق|parked|geparkt|garage)/i.test(text)) {
    const terms = text.split(/[^\p{L}\p{N}:+.-]+/u).filter(x=>x.length>=2).slice(0,10);
    return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:"حفظت مكان السيارة والتفاصيل المحيطة به حتى تقدر تلاقيه بالبحث.",classification:"note",labels:["مكان","سيارة"],keywords:terms,summary:text.slice(0,420),confidence:0.9,actions:[]};
  }

  const explicitReminder = /(?:^|\s)(?:ذكرني|ذكّرني|ذكريني|ذكّريني|اعمل(?:لي)?\s+تذكير|أعمل(?:لي)?\s+تذكير|سوي(?:لي)?\s+تذكير|remind\s+me|erinnere\s+mich)(?:\s|$)/i.test(text);
  if (explicitReminder) {
    const today = has("اليوم","heute","today");
    let passedToday = false;
    if (today && time) {
      try {
        const n = new Date(nowRaw);
        const [h,m] = time.split(":").map(Number);
        passedToday = h < n.getHours() || (h === n.getHours() && m <= n.getMinutes());
      } catch {}
    }
    if (passedToday) {
      return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:"الساعة "+time+" اليوم مرّت بالفعل. هل تقصد غداً بنفس الوقت أم وقتاً آخر؟",classification:"reminder",labels:["تذكير"],keywords:time?[time]:[],summary:text.slice(0,320),confidence:0.88,actions:[]};
    }
    const args: any = {title:"تذكير مساحاتي",body:text.slice(0,500)};
    const delay = text.match(/بعد\s+(\d{1,5})\s*(?:دقيقة|دقائق|دقايق)/i)?.[1];
    if (delay) args.delay_minutes = Number(delay);
    if (day) { args.day_of_week = day; args.repeat = "weekly"; }
    if (time) args.time = time;
    if (has("كل يوم","يومياً","يوميا","daily")) args.repeat = "daily";
    if (delay || (time && (day || today || args.repeat === "daily"))) {
      return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:"فهمت موعد التذكير وسأحاول إنشاء تنبيه أندرويد فعلياً.",classification:"reminder",labels:["تذكير"],keywords,summary:text.slice(0,320),confidence:0.87,actions:[{type:"create_reminder",args,requires_confirmation:false}]};
    }
    return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:day?"فهمت اليوم "+day+"، لكن أحتاج الساعة.":time?"فهمت الساعة "+time+"، لكن أحتاج اليوم أو التاريخ.":"أحتاج اليوم والوقت حتى أنشئ التذكير.",classification:"reminder",labels:["تذكير"],keywords,summary:text.slice(0,320),confidence:0.82,actions:[]};
  }

  const pinSpaceIntent = /(?:^|\s)(?:ثب[ّت]|ثبت|تثبيت)\s+(?:هالمساحة|هذه\s+المساحة|المساحة)(?:\s|$)/i.test(text);
  if (pinSpaceIntent) {
    return {ok:true,engine:"edge-fallback-v9",model:"rules-v9",reply:"فهمت أنك تريد تثبيت هذه المساحة.",classification:"command",labels:["تثبيت"],keywords:[spaceTitle],summary:text.slice(0,320),confidence:0.98,actions:[{type:"pin_space",args:{space_name:spaceTitle},requires_confirmation:false}]};
  }

  const renameDocMatch = text.match(/(?:غي[ّر]\s+اسم|سمي|سمّي|rename)\s+(?:آخر|اخر)\s+(?:ورقة|مستند|ملف)(?:\s+(?:إلى|الى|لـ|ل|باسم))?\s*[«"']?(.+?)[»"']?\s*[؟?]?$/i);
  if (renameDocMatch) {
    const newName = String(renameDocMatch[1] || "").trim().replace(/[؟?]+$/g,"").slice(0,180);
    if (newName) return {ok:true,engine:"edge-fallback-v9",model:"rules-v9",reply:"فهمت: تغيير اسم آخر مستند إلى «"+newName+"».",classification:"command",labels:["مستند","إعادة تسمية"],keywords:[newName],summary:text.slice(0,320),confidence:0.98,actions:[{type:"rename_last_document",args:{new_name:newName},requires_confirmation:false}]};
  }

  const moveDocMatch = text.match(/(?:انقل|نقل|حر[ّ]?ك|move)\s+(?:آخر|اخر)\s+(?:ورقة|مستند|ملف)\s+(?:إلى|الى|لـ|ل)?\s*(?:مساحة|space)\s*[«"']?(.+?)[»"']?\s*[؟?]?$/i);
  if (moveDocMatch) {
    const target = String(moveDocMatch[1] || "").trim().replace(/[؟?]+$/g,"").slice(0,120);
    if (target) return {ok:true,engine:"edge-fallback-v9",model:"rules-v9",reply:"فهمت: نقل آخر مستند إلى مساحة «"+target+"».",classification:"command",labels:["مستند","نقل"],keywords:[target],summary:text.slice(0,320),confidence:0.98,actions:[{type:"move_last_document",args:{target_space:target},requires_confirmation:false}]};
  }

  const renameSpaceMatch = text.match(/(?:غي[ّر]\s+اسم|سمي|سمّي|rename)\s+(?:هالمساحة|هذه\s+المساحة|المساحة)(?:\s+(?:إلى|الى|لـ|ل|باسم))?\s*[«"']?(.+?)[»"']?\s*[؟?]?$/i);
  if (renameSpaceMatch) {
    const newName = String(renameSpaceMatch[1] || "").trim().replace(/[؟?]+$/g,"").slice(0,120);
    if (newName) return {ok:true,engine:"edge-fallback-v9",model:"rules-v9",reply:"فهمت: تغيير اسم هذه المساحة إلى «"+newName+"».",classification:"command",labels:["مساحة","إعادة تسمية"],keywords:[newName],summary:text.slice(0,320),confidence:0.98,actions:[{type:"rename_space",args:{new_name:newName},requires_confirmation:false}]};
  }

  const moveItemMatch = text.match(/(?:انقل|نقل|حر[ّ]?ك|move)\s+(?:آخر|اخر)\s+(?:شي|شيء|عنصر|ملاحظة)(?:\s+(?:إلى|الى|لـ|ل))?\s*(?:مساحة|space)\s*[«"']?(.+?)[»"']?\s*[؟?]?$/i);
  if (moveItemMatch) {
    const target = String(moveItemMatch[1] || "").trim().replace(/[؟?]+$/g,"").slice(0,120);
    if (target) return {ok:true,engine:"edge-fallback-v9",model:"rules-v9",reply:"فهمت: نقل آخر عنصر محفوظ إلى مساحة «"+target+"».",classification:"command",labels:["نقل"],keywords:[target],summary:text.slice(0,320),confidence:0.98,actions:[{type:"move_last_item",args:{target_space:target},requires_confirmation:false}]};
  }

  if (has("أرشف","ارشف","أرشفة","ارشفة")) {
    return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:"فهمت أنك تريد أرشفة هذه المساحة.",classification:"command",labels:["أرشفة"],keywords:[],summary:text.slice(0,320),confidence:0.9,actions:[{type:"archive_space",args:{space_name:spaceTitle},requires_confirmation:false}]};
  }

  if (has("دوام","مناوبة","شفت","arbeit","schicht","dienstplan","arbeitszeit") && !/(مو|مش|ليس)\s+(?:جدول\s+)?دوام/i.test(text)) {
    return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:day&&time?"هذه معلومة دوام لـ"+day+" الساعة "+time+".":"صنفتها كمعلومة دوام لتظهر بسهولة في البحث.",classification:"work_schedule",labels:["دوام"],keywords,summary:text.slice(0,420),confidence:0.8,actions:[]};
  }

  if (has("جواز","عقد","فاتورة","وثيقة","مستند","pdf","rechnung","vertrag","bescheid")) {
    return {ok:true,engine:"edge-fallback-v6",model:"rules-v6",reply:"صنفت المحتوى كمستند وربطت به كلمات بحث مفيدة.",classification:"document",labels:["مستند"],keywords:[],summary:text.slice(0,420),confidence:0.72,actions:[]};
  }

  const genericTerms = text.split(/[^\p{L}\p{N}:+.-]+/u).map(x=>x.trim()).filter(x=>x.length>=2 && !["هذا","هذه","هاي","هاد","على","الى","إلى","من","في","عن","مع","كل","عندي","عنده","بدي"].includes(x)).slice(0,8);
  return {ok:true,engine:"edge-fallback-v7",model:"rules-v7",reply:genericTerms.length ? "حفظتها كملاحظة عن "+genericTerms.slice(0,3).join("، ")+"، وفهرست الكلمات المهمة للبحث." : "حفظت الملاحظة كما هي، ولم أجد فيها تصنيفاً أدق بدون تخمين.",classification:"note",labels:["ملاحظة"],keywords:genericTerms,summary:text.slice(0,420),confidence:0.7,actions:[]};
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: { ...headers, "Access-Control-Allow-Headers": "apikey, content-type, authorization" } });
  if (req.method !== "POST") return out({ ok:false, error:"method_not_allowed" }, 405);
  if ((req.headers.get("apikey") ?? "") !== PUBLISHABLE_KEY) return out({ ok:false, error:"unauthorized" }, 401);

  let body: any;
  try { body = await req.json(); } catch { return out({ ok:false, error:"invalid_json" }, 400); }

  const text = clip(body?.text, 6000);
  if (!text) return out({ ok:false, error:"empty_text" }, 400);

  const spaceTitle = clip(body?.spaceTitle, 120) || "مساحة شخصية";
  const mode = clip(body?.mode, 40) || "chat";
  const strategy = clip(body?.strategy, 24) || "auto";
  const nowRaw = clip(body?.now, 100) || new Date().toISOString();
  const timezone = clip(body?.timezone, 80) || "UTC";
  const recent = buildContext(Array.isArray(body?.recent) ? body.recent : []);
  const appState = body?.appState && typeof body.appState === "object" ? body.appState : {};

  const requestedAction = explicitAction(text);
  if (requestedAction) {
    const routed = localFallback(text, spaceTitle, recent, nowRaw);
    routed.actions = supportedActions(routed.actions, text);
    if (routed.actions.length) return out(routed);
  }
  const doc = appState?.currentDocument || [...recent].reverse().find((m:any) => m.kind === "file");
  const dateReply = documentDateAnswer(text, doc);
  if (dateReply) return out({ok:true,engine:"hybrid-router-v3",model:"deterministic",reply:dateReply,
    classification:"document",labels:["مستند"],keywords:[],summary:dateReply,confidence:0.95,actions:[]});

  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("cf-connecting-ip") ?? "unknown").split(",")[0].trim();
  if (SERVICE_KEY) {
    const clientHash = await hash(ip + "|masahati-agent-v10");
    const quota = await admin.rpc("consume_agent_dev_quota", { p_client_hash:clientHash, p_limit:500 });
    if (quota.error || quota.data !== true) return out(unavailable(text));
  }

  const messages = buildConversation({ ...body, text, spaceTitle, now: nowRaw, timezone });
  // One bounded request allows the provider to finish reasoning instead of aborting it after 6.5s.
  // Preserve the existing provider and quota. Report the model returned by it (it may route elsewhere).
  let provider;
  try { provider = providerConfig(key => Deno.env.get(key), strategy === "quality"); }
  catch { return out(unavailable(text)); }
  const requestedModel = provider.model;
  const response = await askModel(provider, 24_000, messages);
  if (response) {
    return out({ ok:true, engine:"remote-ai-v12", model:response.model, requestedModel, strategy,
      ...normalize(response.parsed, text), actions:[] });
  }
  return out(unavailable(text));

});
