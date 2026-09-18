// Synthetic probes only. Run: node scripts/evaluate-assistant.mjs [--live-agent]
// Live mode uses the app's existing public endpoint/key; no user documents are sent.
import { buildConversation, parseModelEnvelope } from "../supabase/functions/masahati-agent-dev/conversation.ts";
const live = process.argv.includes("--live-agent");
const cases = [
  {name:"arithmetic", text:"عندي 3 صناديق، بكل صندوق 4 كتب. أعطيت صاحبي 5 كتب. كم كتاب بقي؟", expected:[/7|٧|سبع/], forbidden:[/8 كتب|٨ كتب|ثمانية/]},
  {name:"correction", text:"اذكر مكان المفاتيح الحالي فقط.", recent:[
    {role:"user",text:"حطيت المفاتيح بالعلبة الزرقا داخل الخزانة."},
    {role:"assistant",text:"المفاتيح في العلبة الزرقاء داخل الخزانة."},
    {role:"user",text:"تصحيح: نقلت المفاتيح للكيس الأخضر جنب الباب، مو بالعلبة."}],
    expected:[/أخضر|اخضر|الخضراء/,/باب/], forbidden:[/خزانة|زرقاء/]},
  {name:"pronoun",text:"وينه هلأ؟",recent:[{role:"user",text:"جواز السفر كان بالدرج. نقلته اليوم للشنطة السوداء جنب السرير."}],expected:[/سوداء|سودا/,/سرير/]},
  {name:"document_question",text:"ما المبلغ المطلوب؟",appState:{currentDocument:{displayName:"invoice.pdf",ocrText:"Rechnung Nr. AB-771. Gesamtbetrag: 84,50 EUR. Fällig am 18.10.2026."}},expected:[/84[,.]50|٨٤[،.]٥٠/]},
  {name:"missing_information",text:"ما اسم صاحب هذا العقد؟",appState:{currentDocument:{displayName:"contract.pdf",ocrText:"MIETVERTRAG. Vertragsnummer X-7319. Vertragsende 30.09.2027."}},expected:[/غير|لا |ما |لم /]},
  {name:"negated_action",text:"لا تنقل آخر ملف ولا تعمل مساحة جديدة؛ فقط قل إنك فهمت.",expected:[/فهم|حاضر/]},
];
let failed = 0;
for (const probe of cases.filter(p => !process.argv.find(a => a.startsWith("--case=")) || p.name === process.argv.find(a => a.startsWith("--case=")).split("=")[1])) {
  const input = {text:probe.text,recent:probe.recent||[],appState:probe.appState||{},spaceTitle:"اختبار اصطناعي",now:"2026-09-17T20:00:00+02:00",timezone:"Europe/Berlin",mode:"chat"};
  const started = Date.now();
  try {
    const response = await fetch(live ? "https://hxrvlvqlkfylbjicdfzs.supabase.co/functions/v1/masahati-agent-dev" : "https://blockrun.ai/api/v1/chat/completions", {
      method:"POST", headers:{"Content-Type":"application/json", ...(live ? {apikey:"sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3"} : {})},
      body:JSON.stringify(live ? input : {model:process.argv.find(a => a.startsWith("--model="))?.slice(8) || "nvidia/nemotron-3.5-lightning",messages:buildConversation(input),temperature:0.05,max_tokens:1050,stream:false}),
      signal:AbortSignal.timeout(28_000),
    });
    const envelope = await response.json();
    const parsed = live ? envelope : parseModelEnvelope(envelope)?.parsed;
    const reply = parsed?.reply || "";
    const passed = response.ok && probe.expected.every(re=>re.test(reply)) && !(probe.forbidden||[]).some(re=>re.test(reply)) &&
      Array.isArray(parsed?.actions) && parsed.actions.length === 0 && !(parsed?.engine||"").includes("fallback");
    if (!passed) failed++;
    console.log(JSON.stringify({case:probe.name,passed,ms:Date.now()-started,model:envelope.model,engine:parsed?.engine,reply,...(!passed ? {finishReason:envelope.choices?.[0]?.finish_reason,rawContent:envelope.choices?.[0]?.message?.content,error:envelope.error} : {})}));
  } catch (error) { failed++; console.log(JSON.stringify({case:probe.name,passed:false,error:error.message})); }
}
process.exitCode = failed ? 1 : 0;
