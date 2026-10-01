// Required modes: --fixtures (offline) or --live (four synthetic service probes).
// Facts mismatching HTTP 200 still fail. No retries and no fallback-as-semantic success.
import { readFile, mkdir, writeFile, appendFile } from "node:fs/promises";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { semanticReading } from "../supabase/functions/masahati-document-alpha-v2/document-provider.ts";
import { understandDocument } from "../supabase/functions/masahati-document-alpha-v2/document-understanding.ts";
import { parseModelEnvelope, supportedActions } from "../supabase/functions/masahati-agent-dev/conversation.ts";
import { BACKEND_PROBES, inspectProbe, reportExitCode, SERVICE_BASE, PUBLISHABLE_KEY } from "./backend-probes.mjs";

export async function loadFixtures() {
  return JSON.parse(await readFile(new URL("./fixtures/backend-model-responses.json", import.meta.url), "utf8"));
}

export async function fixtureResponse(probe, fixtures) {
  const saved = fixtures[probe.name];
  if (!saved) throw new Error("missing_synthetic_fixture");
  const envelope = { model: saved.model, choices: [{ finish_reason: "stop", message: { content: JSON.stringify(saved.raw) } }] };
  if (probe.kind === "document") {
    let requests = 0;
    const read = await semanticReading(probe.request.ocrText, () => undefined, async (_url, init) => {
      requests++;
      if (JSON.parse(JSON.parse(init.body).messages[1].content).ocr_text !== probe.request.ocrText)
        throw new Error("fixture_request_changed");
      return Response.json(envelope);
    });
    if (requests !== 1) throw new Error("unexpected_fixture_request_count");
    return understandDocument(read.raw, probe.request.ocrText, probe.request.displayName, { method: "semantic", model: read.model });
  }
  const parsed = parseModelEnvelope(envelope);
  if (!parsed) throw new Error("invalid_agent_fixture");
  return { ...saved.raw, ...parsed.parsed, ok: true, model: parsed.model, engine: "fixture-agent",
    actions: supportedActions(saved.raw.actions, probe.request.text) };
}

export async function runProbes({ mode, fixtures, fetcher = fetch, onResult = () => {} }) {
  if (!["fixtures", "live"].includes(mode)) throw new Error("explicit_probe_mode_required");
  const results = [];
  for (const probe of BACKEND_PROBES) {
    const started = Date.now();
    let httpStatus = mode === "fixtures" ? 200 : 0, body = null, transportError = null;
    if (mode === "fixtures") body = await fixtureResponse(probe, fixtures);
    else {
      try {
        const response = await fetcher(`${SERVICE_BASE}/${probe.endpoint}`, {
          method: "POST", headers: { "Content-Type": "application/json", apikey: PUBLISHABLE_KEY },
          body: JSON.stringify(probe.request), signal: AbortSignal.timeout(probe.timeoutMs), redirect: "error",
        });
        httpStatus = response.status;
        try { body = await response.json(); }
        catch (error) {
          if (error?.name === "AbortError" || error?.name === "TimeoutError") transportError = "request_timeout";
          else if (error instanceof TypeError) transportError = "response_unavailable";
          // Invalid JSON remains invalid_response or the original non-2xx HTTP
          // error. Never print the upstream body or reinterpret it as success.
        }
      } catch (error) {
        transportError = ["AbortError", "TimeoutError"].includes(error?.name) ? "request_timeout" : "request_unavailable";
      }
    }
    const result = inspectProbe(probe, { httpStatus, body, transportError, elapsedMs: Date.now() - started });
    results.push(result);onResult(result); // Record each mismatch before exiting; do not stop at the first contract.
  }
  return { mode, synthetic_only: true, live_provider_contacted: mode === "live", semantic_quality_claim: false, results,
    passed: results.filter(r => r.passed).length, failed: results.filter(r => !r.passed).length, exit_code: reportExitCode(results),
    note: mode === "fixtures" ? "Hand-authored provider fixtures test code deterministically, not live model quality."
      : "Each document must be semantic and match the expected facts. Transient failures still fail; there are no runner retries." };
}

async function main() {
  const args = process.argv.slice(2), live = args.includes("--live"), offline = args.includes("--fixtures");
  if (live === offline) throw new Error("Choose exactly one of --fixtures or --live");
  const reportIndex = args.indexOf("--report"), output = reportIndex >= 0 ? args[reportIndex + 1] : null;
  if (reportIndex >= 0 && (!output || output.startsWith("--"))) throw new Error("--report requires a file path");
  const allowed = args.filter((_, i) => i !== reportIndex && i !== reportIndex + 1);
  if (reportIndex < 0 ? args.some(a => !["--live", "--fixtures"].includes(a)) : allowed.some(a => !["--live", "--fixtures"].includes(a)))
    throw new Error("Unknown probe argument");
  const report = await runProbes({ mode: live ? "live" : "fixtures", fixtures: offline ? await loadFixtures() : undefined,
    onResult: result => console.log(JSON.stringify(result)) });
  if (output) { await mkdir(dirname(output), { recursive: true });await writeFile(output, JSON.stringify(report, null, 2) + "\n"); }
  console.log(JSON.stringify({ mode: report.mode, passed: report.passed, failed: report.failed, exit_code: report.exit_code, note: report.note }));
  if (process.env.GITHUB_STEP_SUMMARY) await appendFile(process.env.GITHUB_STEP_SUMMARY,
    `## Backend ${report.mode}\n\n${report.passed} passed; ${report.failed} failed. Exit ${report.exit_code}.\n\n` +
    "```json\n" + JSON.stringify(report.results.map(r => ({ case: r.case, status: r.status, http_status: r.http_status,
      analysis_method: r.analysis_method, model: r.model, degraded_reason: r.degraded_reason, issue_codes: r.issue_codes,
      mismatches: r.mismatches })), null, 2) + "\n```\n");
  process.exitCode = report.exit_code;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
