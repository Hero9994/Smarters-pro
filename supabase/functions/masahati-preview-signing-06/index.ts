// Temporary, deny-by-default release credential exchange. Not used by the app.
// postgres.js 3.4.7: Unlicense, https://github.com/porsager/postgres/tree/v3.4.7
import postgres from "npm:postgres@3.4.7";
import policy from "./policy.json" with { type: "json" };
const encoder = new TextEncoder();
function bytes(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw Error("Invalid token");
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, (x) => x.charCodeAt(0));
}
async function claims(token: string) {
  if (token.length > 16000) throw Error("Invalid token");
  const parts = token.split(".");
  if (parts.length !== 3) throw Error("Invalid token");
  const header = JSON.parse(new TextDecoder().decode(bytes(parts[0])));
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw Error("Invalid token");
  const response = await fetch("https://token.actions.githubusercontent.com/.well-known/jwks",
    { signal: AbortSignal.timeout(10000), redirect: "error" });
  if (!response.ok) throw Error("Identity unavailable");
  const jwks = await response.json();
  const key = jwks.keys.find((k: JsonWebKey & { kid: string }) =>
    k.kid === header.kid && k.kty === "RSA" && k.alg === "RS256" && k.use === "sig");
  if (!key) throw Error("Invalid token");
  const publicKey = await crypto.subtle.importKey("jwk", key,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey,
    bytes(parts[2]).buffer as ArrayBuffer, encoder.encode(parts[0] + "." + parts[1]));
  if (!valid) throw Error("Invalid token");
  const c = JSON.parse(new TextDecoder().decode(bytes(parts[1])));
  const now = Math.floor(Date.now() / 1000);
  if (c.iss !== "https://token.actions.githubusercontent.com" ||
      c.aud !== "masahati-preview-signing-0.6" ||
      !Number.isFinite(c.exp) || !Number.isFinite(c.iat) || c.exp <= now ||
      c.iat > now + 30 || c.iat < now - 600 || c.exp > now + 1200 ||
      (c.nbf !== undefined && (!Number.isFinite(c.nbf) || c.nbf > now + 30)) ||
      c.repository !== "Hero9994/Smarters-pro" || String(c.repository_id) !== "1168585627" ||
      c.ref !== "refs/heads/alpha/scanner-professional-2026-10-01" ||
      c.workflow_ref !== "Hero9994/Smarters-pro/.github/workflows/scanner-release-0.6.yml@refs/heads/alpha/scanner-professional-2026-10-01" ||
      c.event_name !== "push" || c.runner_environment !== "github-hosted" ||
      c.sha !== policy.workflow_sha || c.workflow_sha !== policy.workflow_sha ||
      String(c.run_id) !== String(policy.release_run) || String(c.run_attempt) !== "1")
    throw Error("Unauthorized identity");
  return c;
}
Deno.serve(async (req: Request) => {
  const noCache = { "Cache-Control": "no-store", "Content-Type": "application/json" };
  if (policy.disabled || Date.now() >= Date.parse(policy.expires_at))
    return new Response('{"error":"Release exchange retired"}', { status: 410, headers: noCache });
  if (req.method !== "POST") return new Response('{"error":"Method not allowed"}', { status: 405, headers: noCache });
  let stage = "identity";
  try {
    const authorization = req.headers.get("authorization") || "";
    if (!authorization.startsWith("Bearer ")) throw Error("Invalid token");
    await claims(authorization.slice(7));
    stage = "database";
    const db = postgres(Deno.env.get("SUPABASE_DB_URL")!, {
      prepare: false, max: 1, idle_timeout: 5, connect_timeout: 10,
      ssl: "require", onnotice: () => {}
    });
    try {
      // The two named, existing backups only; no schema/data/secret mutation.
      const rows = await db`select name,decrypted_secret from vault.decrypted_secrets
        where name in ('github_masahati_token','masahati_preview_signing_v1')`;
      const token = rows.find((r) => r.name === "github_masahati_token")?.decrypted_secret;
      const material = rows.find((r) => r.name === "masahati_preview_signing_v1")?.decrypted_secret;
      if (!token || !material) throw Error("Unavailable");
      async function github(path: string) {
        const response = await fetch("https://api.github.com/repos/Hero9994/Smarters-pro/" + path,
          { headers: { Authorization: "Bearer " + token, Accept: "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Masahati-preview-release" },
            redirect: "error", signal: AbortSignal.timeout(10000) });
        if (!response.ok) throw Error("Unavailable");
        return await response.json();
      }
      stage = "ci";
      const run = await github("actions/runs/" + policy.ci_run);
      const jobs = await github("actions/runs/" + policy.ci_run + "/jobs?per_page=100");
      const required = ["verify","backend-regression","instrumented (26)","instrumented (36)"];
      if (run.head_sha !== policy.source_sha || run.status !== "completed" ||
          run.conclusion !== "success" || run.path !== ".github/workflows/android-ci.yml" ||
          !required.every((name) => jobs.jobs.some((j: {name:string;status:string;conclusion:string}) =>
            j.name === name && j.status === "completed" && j.conclusion === "success")))
        throw Error("CI gate rejected");
      stage = "release";
      const release = await github("actions/runs/" + policy.release_run);
      if (release.head_sha !== policy.workflow_sha || release.status !== "in_progress" ||
          release.run_attempt !== 1 || release.event !== "push" || release.path !== ".github/workflows/scanner-release-0.6.yml")
        throw Error("Release gate rejected");
      stage = "key-identity";
      const parsed = JSON.parse(material);
      if (parsed.alias !== "masahati-preview" || parsed.package !== "app.masahati.mobile.preview" ||
          parsed.certificate_sha256 !== "134b86f90a4d739167f9890139fefb665979c410fa2b011e3b82e7bd1bb0cd9a")
        throw Error("Identity gate rejected");
      // Never log the JWT, Vault rows, signing material or database errors.
      return new Response(JSON.stringify(parsed), { status: 200, headers: noCache });
    } finally { await db.end({ timeout: 2 }); }
  } catch {
    return new Response(JSON.stringify({ error: "Unauthorized or release gate unavailable", stage }), { status: 403, headers: noCache });
  }
});
