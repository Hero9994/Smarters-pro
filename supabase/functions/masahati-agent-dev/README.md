# Masahati assistant continuation

Recovered from production version 27, then changed to role-preserving dialogue,
latest-correction precedence, strict model-output validation and a separate command path.
Model output cannot authorize a mutation. Android also independently gates execution.

The existing provider is BlockRun's public model route. Synthetic evaluation on
2026-09-17 succeeded for correction, pronouns, a German invoice amount and a missing
contract owner. It also timed out and returned `FREE_MODEL_FAILED` (free capacity
exhausted). This route is **not a reliable production AI service**. Returned `model`
now records the actual provider-reported model, which may differ from the requested one.

## Controlled provider configuration

The backend accepts these **server-side secrets only**, never Android request values:

- `MASAHATI_CHAT_URL`: HTTPS chat-completions endpoint.
- `MASAHATI_CHAT_MODEL`: exact model ID verified with the chosen provider.
- `MASAHATI_CHAT_API_KEY`: that provider's credential.

All three must be configured together. Partial/invalid configuration fails closed;
it does not silently send documents to the old provider. No new provider, credential,
account or billing was activated by this change. Never commit keys or put them in an APK.
Before enabling a paid provider, establish a spending cap and real user authentication.
The current publishable API key is public; existing per-IP quota is not user authentication.

## Validation

Pure boundary tests (Node 24):

```
node --test supabase/functions/masahati-agent-dev/*.test.ts
```

Synthetic live evaluation (no personal data):

```
node scripts/evaluate-assistant.mjs --live-agent
```

Live probes report every failure and exit nonzero. They are not deterministic CI gates.
They do not establish reliability from one successful run or from HTTP 200 alone.
