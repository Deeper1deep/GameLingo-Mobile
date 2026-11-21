# Dual Arabic translation modes — implementation report (brief 8)

Base: v0.4.2 stability audit branch. Branch: `feature/arabic-modes`. 284 unit tests green.

## What changed

| File | Change |
| --- | --- |
| `data/settings/SettingsModels.kt` | `ArabicTranslationMode` enum (`MODERN_STANDARD_ARABIC`, `EGYPTIAN_ARABIC`); `AppSettings.arabicTranslationMode`, default **Egyptian** |
| `data/settings/SettingsRepository.kt` + `DataStoreSettingsRepository` | `setArabicTranslationMode`; persisted as `stringPreferencesKey("arabic_translation_mode")`, read with `parseEnumOrDefault` (garbage → Egyptian) |
| `data/settings/FakeSettingsRepository.kt`, SettingsViewModel | setter + fake support |
| `translate/TranslationRequest.kt` | `arabicMode` field (default Egyptian) — one shared setting reaches every engine through the request, not per-engine settings |
| `translate/CacheContext.kt` | `arabicMode` + `promptVersion` are part of the cache identity |
| `translate/TranslationCache.kt` | **bug fix**: entries were keyed by text only, so a translation stored in one mode was evicted by the next line in the other; now keyed by `(text, context)` so MSA and Egyptian entries coexist |
| `translate/ArabicStylePrompt.kt` (new) | `MSA_SYSTEM_PROMPT` + `systemPromptFor(mode)` / `promptVersionFor(mode)`; Egyptian mandate reused verbatim from `EgyptianDialectPrompt` |
| `translate/online/GeminiTranslationEngine` | prompt chosen per `request.arabicMode` |
| `translate/local/TranslateGemmaEngine` | `systemInstructionFor(mode)`: new MSA instruction; dictionary override + polish are Egyptian-only |
| `translate/local/LocalAiTranslationEngine` (Marian) | Egyptian-only dictionary pass; in MSA mode returns raw model output (no Egyptian polish, no `+dict` tag) |
| `translate/local/LocalEngineChain` | ML Kit polish Egyptian-only |
| `translate/local/EgyptianDictionary` | layered glossaries: `common_glossary`, `msa_glossary`, `egyptian_glossary`, `gaming_slang`, `character_names`, `location_names`, `profanity_terms`; mode-aware `glossaryTerms(source, mode)`; local update file verified by version + SHA-256 before install |
| bundled `egyptian_dictionary.json` | v2: layered sections; `msa_glossary` seeded with renderings for terms whose Egyptian form must never appear in MSA (police/car/money…) |
| `translate/TranslationCoordinator` | reads the mode from settings **per request**; feeds mode-aware glossary + prompt version; publish gate `isPublishable(...)` drops in-flight old-mode results when the mode changes mid-session |
| `ui/settings/TranslationSettingsScreen` + strings | Arabic style section: فصحى / العامية المصرية segmented selector with a per-mode Arabic description |
| `app/src/test/.../ArabicTranslationModeTest.kt` (new) | 13 tests covering the brief's checklist |

## How persistence works

The choice is a string in the existing settings DataStore (`arabic_translation_mode`), read on every translation request. Default = Egyptian, so nothing changes for existing users until they pick فصحى. No Room schema, no migration, no wipe.

## Which engines truly support both modes — honest assessment

| Engine | Both modes? | How |
| --- | --- | --- |
| Gemini (online) | **Yes** | receives a dedicated MSA or Egyptian system prompt per request |
| TranslateGemma (local) | **Yes** | separate MSA and Egyptian system instructions; dictionary override/polish only in Egyptian |
| Marian (local) | **Honestly: mode-aware, not bilingual-quality** | the model itself outputs MSA-ish Arabic; the Egyptian layer IS the dictionary post-pass (no prompt exists to claim otherwise). In MSA mode its raw output is already the right register, so MSA quality is fine; Egyptian quality depends entirely on dictionary coverage |
| ML Kit (offline floor) | **Partial** | outputs generic formal Arabic in both modes; in Egyptian mode the dictionary polish can Egyptianize covered terms; in MSA mode output passes through untouched. It has no true Egyptian generation |

## MSA / Egyptian quality per engine

- **Gemini**: best MSA and best Egyptian (prompt-driven, both mandates verified present).
- **Gemma**: good Egyptian (established); MSA instruction is new and concise — solid for game lines, but the model was tuned for chat translation; MSA long-form may read plain.
- **Marian**: MSA fine (native output); Egyptian = dictionary coverage only, mixed register outside covered terms.
- **ML Kit**: formal/generic always; acceptable MSA fallback, weakest Egyptian.

## Does Marian need more work?

Not for this feature: its architecture was already "MSA model + Egyptian dictionary layer", and the mode switch now makes that explicit rather than pretending prompts do anything (the model sees no prompt). A larger Egyptian lexicon would improve Marian's Egyptian, but that is dictionary content, not engine work.

## Profanity / slang tests

`ArabicTranslationModeTest.both mode mandates preserve profanity verbatim and forbid inventing it` verifies the anti-censorship mandate ("Never censor, omit, sanitize, soften…", "Do not invent profanity") in both cloud prompts and both Gemma instructions, and that the established Egyptian mandate text is unchanged. Slang/profanity glossary layers are shared across modes so explicit-term coverage is identical; register layers (MSA vs Egyptian) never cross.

## Cache tests

Two tests prove same-sentence MSA and Egyptian entries are independent and never served across modes. These tests caught a real bug — the cache map was keyed by text only — fixed by keying on `(text, context)`.

## Mid-session change

Switching mode during a session does **not** touch capture, OCR, the overlay, or the session itself: the coordinator reads the setting per request, and capture never reads translation settings. Results already in flight under the old mode are dropped at the publish gate (`isPublishable`: session generation + request mode vs current mode) instead of appearing over the new mode. New lines use the new mode immediately. The pure gate is unit-tested; the UI-level continuity (capture thread uninterrupted) holds by construction — no translation-setting read exists on the capture path — and is stated as such, not claimed as a device test.

## Remaining limitations

1. Persistence round-trip is proven at logic level (fake repository + parse fallback), not via Robolectric/instrumentation.
2. ML Kit has no true Egyptian generation; its Egyptian output is formal Arabic + dictionary polish.
3. Marian's Egyptian quality is bounded by dictionary coverage.
4. Bundled `msa_glossary` is a seed (common mismatches); it grows through the verified local-update file.
5. Capture-continuity on mode change is guaranteed by construction, not by an on-device test.
6. The 100+ bilingual sentence validation from the deep-engine brief is brief 9's scope, not included here.
