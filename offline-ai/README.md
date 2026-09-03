# Ameen Offline Secretary

Internal codename: `Ameen-1.7B-v0`

## Goal
A privacy-first Arabic/German personal secretary that runs on Android without Internet after the model is installed. It should understand Levantine Arabic, Modern Standard Arabic, German administrative vocabulary, scanned-document OCR, dates, deadlines, appointments, contracts, and user organization commands.

## Base model
- `Qwen3-1.7B`
- Android runtime: Google LiteRT-LM
- Target artifact: INT4 `.litertlm` (~1 GB)
- The base model remains general-purpose; domain specialization is added with high-quality SFT/QLoRA, not by training a model from scratch.

## Local pipeline
1. Scanner -> shadow cleanup / perspective correction.
2. Local OCR -> raw text.
3. Deterministic extraction -> dates, times, currencies, document IDs, notice periods.
4. Ameen -> classify, summarize, resolve conversational context, propose actions.
5. Local tools -> search, rename, move, archive, pin, create reminder, create space.
6. SQLite/FTS memory -> searchable facts and document metadata.

## Core document classes
identity, passport, residence_permit, appointment, health_insurance, government_letter, wohngeld, kindergeld, kinderzuschlag, but, employment_contract, rental_contract, insurance_contract, invoice, school_letter, medical_letter, bank_letter, vehicle_document, other.

## Required structured fields
`document_type`, `issuer`, `subject`, `valid_from`, `valid_until`, `appointment_at`, `payment_due`, `notice_period`, `cancellation_deadline`, `renewal_date`, `amount`, `summary`, `keywords`, `confidence`, `needs_user_question`, `suggested_actions`.

## Safety and privacy
- Personal documents are not used as training data by default.
- Training examples should be synthetic or anonymized.
- Personal memory stays in SQLite/RAG so it can be deleted without retraining model weights.
- Destructive/external actions require confirmation.
- The model must never invent a deadline. If a date or clause is ambiguous, it asks one targeted question.

## Evaluation gates
- Arabic Levantine + MSA + Arabic/German code-switching.
- German administrative and contract documents.
- Correct date/notice-period extraction.
- Context follow-up such as: `كل اثنين الساعة 18:30` -> `ذكرني فيها`.
- Search query normalization and semantic retrieval.
- No fabricated fields when OCR is incomplete.
