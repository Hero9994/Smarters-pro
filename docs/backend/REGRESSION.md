# Backend regression and live diagnostics

The earlier live German-contract checks failed after an HTTP 200 response.
Because those runs did not save the response fields, their exact mismatching
field cannot be recovered and a specific quota/outage explanation is unproven.
This change fixes the test design and captures future failures; it does not
claim that the model always analyzes documents correctly.

## Deterministic required regression

`backend-regression` in `.github/workflows/android-ci.yml` runs independently
of the Android build and makes no live service/provider calls:

```sh
node --test supabase/functions/_shared/*.test.ts supabase/functions/masahati-agent-dev/*.test.ts supabase/functions/masahati-document-alpha-v2/*.test.ts scripts/backend-probes.test.mjs
node scripts/evaluate-documents.mjs
node scripts/evaluate-backend.mjs --fixtures --report backend-reports/fixtures.json
```

The original 36 tests and seven conservative-reading cases remain. Ten new
diagnostic tests cover incorrect facts in successful HTTP responses, source
evidence rejection, fallback, configuration/auth failures, transient errors,
malformed JSON, timeouts, continuing after the first failure and no runner retry.
Four hand-authored synthetic provider fixtures exercise the actual document
provider parser/evidence gate and the agent parser/action guard. They are
explicitly labelled fixtures; their success is not live semantic-model accuracy.

No product/backend deployment or provider configuration is changed by these tests.
The existing bounded server-side retry of explicit Gemini 5xx responses remains
covered by the original unit tests; the new live runner adds no retries.

## Independent live workflow

`.github/workflows/backend-live-smoke.yml` runs on relevant backend/probe pushes
or manual workflow dispatch. Ordinary scanner-only edits do not trigger model
requests. There is no scheduled task and no `continue-on-error`.

```sh
node scripts/evaluate-backend.mjs --live --report backend-live-reports/results.json
```

Every probe uses only the existing synthetic input. German and Arabic contracts
must match type, reference, expiry, deadline and required action **and** both
top-level/document `analysis_method == "semantic"`. Valid rule-based extraction
cannot pass that requirement. Agent search and explicit document-date probes
test their supported behavior; their local source-grounded date answer is not
advertised as a semantic-model test.

The runner records all four results, even if the first contract fails. Each
record contains expected/actual fields and mismatches plus `http_status`,
`analysis_method`, `model`, `analysis_status`, `engine`, `degraded_reason`,
`issue_codes` and timing. JSON diagnostics are logged before the final exit and
also stored as artifacts and in the workflow step summary. Raw upstream error
bodies, credentials and real user documents are excluded.

| Exit | Meaning |
| --- | --- |
| 0 | Every listed probe meets its requirements. This is a bounded synthetic smoke check. |
| 1 | Incorrect/missing facts, invalid successful JSON, permanent HTTP/configuration/auth failure, or missing semantic evidence without a reported transient cause. |
| 2 | Unavailable transport, recognized temporary HTTP status, or matching fallback facts with a stable capacity/timeout/unavailable reason. **Still a failed live workflow.** |

An incorrect fact always wins over a simultaneous fallback/transient condition:
it remains `data_mismatch`/exit 1. Stable provider codes identify what the server
reported, not the root cause of earlier runs, nor proof that an error will clear
on retry. A later successful probe does not erase an earlier failure.

## First diagnosed live run

For commit `c205c6eb1353ba7d6e9885e1111c0886c667b3f6`, the required
`backend-regression` job in [Android CI 36916316467](https://github.com/Hero9994/Smarters-pro/actions/runs/36916316467)
passed all 46 unit tests, seven conservative-reading cases and four offline
fixtures. The independent [live run 36916316705](https://github.com/Hero9994/Smarters-pro/actions/runs/36916316705)
failed with exit 1, preserving all four single-request probe results:

| Probe | Actual facts that differ from the expectation | Outcome |
| --- | --- | --- |
| German contract | `expiry_date: ""` instead of `2027-09-30`; `action_required: false` instead of `true` | `data_mismatch`, HTTP 200, semantic, `uncertain_date` |
| Arabic contract | `due_date: ""` instead of `2027-09-15` | `data_mismatch`, HTTP 200, semantic |
| Agent document search | No assertion mismatch | Passed supported search behavior |
| Agent explicit contract expiry | No assertion mismatch | Passed source-grounded date reply |

Both document replies report `gemini-3.1-flash-lite` and an empty
`degraded_reason`. They were not rule-based capacity fallbacks. This records
what the endpoint returned; it does not show the raw model facts/excerpts or
prove why the date was omitted/rejected. No live retry or production deployment
was used to change these results. A read-only fetch confirmed that all six files
of the deployed document function (version 11) exactly match the checked-in
source, ruling out document-function source drift in this run.

The [complete sanitized results](diagnostics/c205c6e/results.json) and
[provenance including source hashes](diagnostics/c205c6e/PROVENANCE.json)
are retained. These newly identified fields cannot retrospectively identify
the mismatches in the two earlier runs. The next backend task is to reproduce
the date omissions/evidence rejection with controlled provider fixtures and
fix them without weakening source-date validation or the live assertions.
