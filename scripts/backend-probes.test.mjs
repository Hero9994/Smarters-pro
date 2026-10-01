import test from "node:test";
import assert from "node:assert/strict";
import { BACKEND_PROBES, inspectProbe, reportExitCode } from "./backend-probes.mjs";
import { fixtureResponse, loadFixtures, runProbes } from "./evaluate-backend.mjs";

const fixtures = await loadFixtures(), contract = BACKEND_PROBES[0];
const correct = () => fixtureResponse(contract, fixtures);

test("saved synthetic provider envelopes exercise the parser and evidence gate offline", async () => {
  const report = await runProbes({ mode: "fixtures", fixtures, fetcher: async () => { throw new Error("network_not_allowed"); } });
  assert.equal(report.exit_code, 0);assert.equal(report.results.length, 4);assert.equal(report.semantic_quality_claim, false);
  for (const result of report.results.slice(0, 2)) {
    assert.equal(result.analysis_method, "semantic");assert.equal(result.model, "fixture-document-model");
  }
});

test("each incorrect HTTP 200 contract fact identifies its expected and actual value", async () => {
  for (const [key, wrong] of Object.entries({ doc_type: "invoice", reference_number: "MV-9999", expiry_date: "2027-09-29", due_date: "2027-09-14", action_required: false })) {
    const body = await correct();body.document[key] = wrong;
    const result = inspectProbe(contract, { body });
    assert.equal(result.status, "data_mismatch");assert.equal(result.exit_code, 1);assert.equal(result.http_status, 200);
    assert.deepEqual(result.mismatches, [{ field: "document." + key, expected: contract.assertions["document." + key], actual: wrong, passed: false }]);
  }
});

test("a model's unsupported reference is rejected and the missing field and issue are observable", async () => {
  const changed = structuredClone(fixtures);changed.german_contract.raw.references[0].value = "MV-9999";
  const result = inspectProbe(contract, { body: await fixtureResponse(contract, changed) });
  assert.equal(result.status, "data_mismatch");assert.equal(result.actual["document.reference_number"], "");
  assert.ok(result.issue_codes.includes("unsupported_field"));
});

test("matching rule fallback can never pass the live semantic requirement", async () => {
  const body = await correct();body.analysis_method = body.document.analysis_method = "rules";
  body.model = "rules-document-v3";body.degraded_reason = "provider_capacity";
  const result = inspectProbe(contract, { body });
  assert.equal(result.passed, false);assert.equal(result.status, "semantic_temporarily_unavailable");assert.equal(result.exit_code, 2);
  assert.equal(result.expected.analysis_method, "semantic");assert.equal(result.actual.analysis_method, "rules");
});

test("authentication and missing semantic evidence are not diagnosed as temporary quota failures", async () => {
  const body = await correct();body.analysis_method = body.document.analysis_method = "rules";body.degraded_reason = "provider_auth";
  const result = inspectProbe(contract, { body });
  assert.equal(result.status, "semantic_required");assert.equal(result.exit_code, 1);assert.equal(result.transient, false);
  delete body.analysis_method;delete body.degraded_reason;
  assert.equal(inspectProbe(contract, { body }).status, "semantic_required");
});

test("an incorrect fact still fails as data mismatch even when fallback reports capacity", async () => {
  const body = await correct();body.document.due_date = "2027-09-14";
  body.analysis_method = body.document.analysis_method = "rules";body.degraded_reason = "provider_capacity";
  assert.equal(inspectProbe(contract, { body }).status, "data_mismatch");
  assert.equal(inspectProbe(contract, { body }).exit_code, 1);
});

test("temporary HTTP failures, auth errors and invalid successful JSON remain failures without leaking bodies", () => {
  const temporary = inspectProbe(contract, { httpStatus: 503, body: { error: "Bearer synthetic-secret" } });
  assert.equal(temporary.exit_code, 2);assert.equal(temporary.passed, false);
  assert.ok(!JSON.stringify(temporary).includes("synthetic-secret"));
  assert.equal(inspectProbe(contract, { httpStatus: 401 }).exit_code, 1);
  assert.equal(inspectProbe(contract, { httpStatus: 200, body: null }).status, "invalid_response");
});

test("the live runner records later probes after a 200 mismatch and never retries", async () => {
  let requests = 0;const recorded = [];
  const report = await runProbes({ mode: "live", onResult: r => recorded.push(r), fetcher: async (_url, init) => {
    const index = requests++;assert.ok(init.signal);
    const body = await fixtureResponse(BACKEND_PROBES[index], fixtures);
    if (index === 0) body.document.reference_number = "wrong-reference";
    return Response.json(body);
  } });
  assert.equal(requests, 4);assert.equal(recorded.length, 4);assert.equal(report.exit_code, 1);assert.equal(report.passed, 3);
  assert.equal(recorded[0].mismatches[0].field, "document.reference_number");
});

test("timeouts and malformed response JSON retain distinct diagnostics and all probes finish", async () => {
  let count = 0;
  const report = await runProbes({ mode: "live", fetcher: async () => {
    if (count++ === 0) throw new DOMException("synthetic-private-message", "TimeoutError");
    return new Response("not JSON");
  } });
  assert.equal(count, 4);assert.equal(report.results[0].status, "transport_unavailable");
  assert.equal(report.results[0].transport_error, "request_timeout");
  assert.equal(report.results[1].status, "invalid_response");assert.equal(report.exit_code, 1);
  assert.ok(!JSON.stringify(report).includes("synthetic-private-message"));
});

test("a data failure is not hidden by an unrelated temporary outage or successful probe", () => {
  assert.equal(reportExitCode([{ exit_code: 0 }, { exit_code: 2 }, { exit_code: 1 }]), 1);
  assert.equal(reportExitCode([{ exit_code: 0 }, { exit_code: 2 }]), 2);
});
