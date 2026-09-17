import { test } from "node:test";
import assert from "node:assert/strict";
import { normalizedDate, validateResultDates } from "./document-dates.ts";

test("real calendar dates normalize consistently across German and ISO formats", () => {
  assert.equal(normalizedDate("30.09.2027"), "2027-09-30");
  assert.equal(normalizedDate("1/2/2028"), "2028-02-01");
  assert.equal(normalizedDate("2028-02-29"), "2028-02-29");
  assert.equal(normalizedDate("29.02.2000"), "2000-02-29");
});

test("impossible dates and partial date strings are rejected", () => {
  for (const raw of ["31.02.2027", "2027-02-31", "29.02.2027", "29.02.1900", "31.04.2027", "2027-00-10", "2027-13-10", "2027-01-00", "am 30.09.2027", "2027-09-30 tomorrow", "", null]) {
    assert.equal(normalizedDate(raw), "", String(raw));
  }
});

test("model dates cannot reintroduce impossible deadlines or supporting evidence", () => {
  const source = {
    reply: "ينتهي: 2027-02-31", summary: "تنتهي الوثيقة في 2027-02-31", actions: [],
    document: {
      expiry_date: "2027-02-31", due_date: "2028-02-29", issue_date: null,
      evidence: [{ field: "expiry_date", excerpt: "31.02.2027" }, { field: "due_date", excerpt: "29.02.2028" }],
    },
  };
  const result = validateResultDates(source);
  assert.equal(result.document.expiry_date, "");
  assert.equal(result.document.due_date, "2028-02-29");
  assert.equal(result.document.issue_date, "");
  assert.equal(result.document.needs_date_review, true);
  assert.deepEqual(result.document.evidence.map((item: any) => item.field), ["due_date"]);
  assert.ok(!result.reply.includes("2027-02-31"));
  assert.equal(source.document.expiry_date, "2027-02-31", "input is not mutated");
});
