# Deep Translation Engine Fix — Final Report

This document is the final report required by the deep-engine brief: the eight
mandatory observed-error cases with root causes, what was built, honest
per-engine capabilities, test evidence with methodology, latency and memory
impact, and what remains unsolved.

## 1. The eight mandatory cases: root causes and fixes

Root causes were identified from the code, then confirmed by running the real
shipped Marian EN→AR models (`tools/marian_eval.py`, results in section 4).

| # | Source | Root cause | Fix |
|---|--------|-----------|-----|
| 1 | "Yeah, see you around." | Marian translates literally: raw output is "نعم، أراك في الجوار" (measured, section 4). The idiom "see you around" was unknown to every layer. | Understanding dataset entry with per-mode renderings (MSA "أراك لاحقًا", Egyptian "أشوفك على خير"), delivered as a prompt hint to instruction-following engines; whole-line replacement for literal engines when a line IS the idiom. |
| 2 | "Good man. Hey, you stay out of trouble, alright?" | Literal multi-part dialogue rendering; tone lost. "stay out of trouble" and "alright?" (seeking agreement) unknown. | Both expressions added to the dataset with dialogue-tone renderings ("اسمع", "إياك أن تورّط نفسك", "اتفقنا؟"). |
| 3 | "Military's gonna be out in force soon." | "out in force" parsed word-by-word (the shipped app produced "ساري المفعول" = "in effect", from the legal sense of "in force"). | Dataset idiom entry: MSA "ينتشر بقوة", Egyptian "نازل بكثافة"; "gonna" informal entry keeps the future tense. |
| 4 | "The truth. I got no idea where he's hiding." | "I got no idea" misunderstood; double negation lost. | Dataset informal entry "i got no idea" → "ليس لديّ أدنى فكرة" / "معنديش أي فكرة". (Measured raw Marian output on this line is already close; the layer guarantees it for every engine.) |
| 5 | "Well, what did you tell her?" | Control case: already correct, must not regress. | Locked by corpus case 5: expected tokens exist, quality gate must not alter a correct question. Measured raw output "حسناً، ماذا أخبرتها؟" passes the gate untouched. |
| 6 | "You think she'd tell me?" | Contraction + epistemic "you think" + future-in-question. Shipped output was present-tense MSA. | Dialogue expression entry → "أتظن أنها ستخبرني؟". Measured: raw Marian was already acceptable; the whole-line match now returns the expected rendering exactly, and the quality gate restores the dropped question mark (measured, section 4). |
| 7 | "Marlene? What do the Fireflies need with Robert?" | Faction name translated literally ("اليراعات" was actually the EXPECTED term here; the error report showed a generic-noun rendering plus broken question structure "ماذا تحتاج اليراعات مع"). | Gaming glossary `factions` layer pins Fireflies → "اليراعات" as a proper faction name in both modes; "what do X need with Y" added as a dialogue expression with the "ما الذي يريده … من …" structure. |
| 8 | "Uh... Marlene. She's been asking around, trying to find him." | "ask around" unknown; unnatural tense choice. | Phrasal verb "ask around" → "يسأل عنه في كل مكان" / "بيلف يسأل عليه"; present-perfect handling is in the MSA prompt rules (section 5). |

## 2. What was built (files)

- `core/understanding/EnglishUnderstanding.kt` + `EnglishUnderstandingHolder.kt`
  + `assets/understanding/english_expressions.json` (v2, 70 entries across
  idioms / phrasal verbs / informal / dialogue expressions): the English
  understanding layer. Detection is longest-first, word-bounded,
  non-overlapping; entries carry a plain-English meaning plus MSA and Egyptian
  renderings; context (the game's own line) decides which entries match —
  no blind string replacement of engine output.
- `ContextMemory`: timestamped `add`, 60-second gap or mode switch starts a
  new scene (scene separation); the coordinator feeds the last lines of the
  CURRENT scene only. Legacy callers untouched.
- `TranslationRequest.understandingHints`: per-line hints ("see you around" is
  an idiom meaning X, render as Y) rendered into the Egyptian dialect prompt
  and the Gemma prompt; cache key includes the understanding dataset version.
- `LocalEngineChain.correctLiteralIdiom`: when a whole line IS one known
  expression, Marian/ML Kit output is replaced with its verified rendering and
  tagged `+idiom`. Never applied to the prompt engine.
- Gaming glossary v3 (`assets/local_ai/egyptian_dictionary.json`): `factions`,
  `weapons`, `military_terms`, `missions` layers (shared by both modes) plus
  seeded gaming slang; name consistency comes from pinning one Arabic form per
  English term. User-added terms ("مصطلحاتي" settings section): stored in
  DataStore, merged over the bundled glossary (user terms win), included in
  the cache signature.
- MSA prompt v2: contemporary MSA, tense/question/negation fidelity, no
  padding; the Gemma MSA instruction line matches.
- `core/quality/TranslationQualityGate.kt`: post-engine validation with no
  extra model calls and no retranslation loop. Lost question mark is restored
  locally (`+qc` tag); lost negation and untranslated lines are detected and
  reported, never rewritten, published once.
- `tools/marian_eval.py`: reproducible before/after harness over the real
  shipped ONNX models (Python port of the app's tokenizer and post-engine
  pipeline).
- Tests: `RegressionCorpusTest` + corpus (`app/src/test/resources/
  regression_corpus.json`, 123 cases, 16 categories), understanding layer
  tests, quality-gate tests, context-memory scene tests, glossary/user-term
  tests, coordinator/chain fakes updated. 329 unit tests, all green.

## 3. Per-engine capability review (honest)

| Capability | Marian (local ONNX) | ML Kit (fallback) | Gemma (on-device LLM) |
|---|---|---|---|
| Produces each translation | yes | yes | yes |
| Uses conversation context | no (single-line encoder) | no | yes (prompt) |
| Follows text instructions | NO — an encoder-decoder MT model; prompts mean nothing to it | NO | yes |
| Needs dictionary / pre- / post-processing | YES — this is how it is improved (glossary pins, whole-line idiom replacement, quality gate). We do not claim it understands hints. | YES — same treatment, plus the Egyptian dictionary polish in Egyptian mode (`+dict`) | benefits from hints/glossary in-prompt |
| Produces real MSA | generic written Arabic, close to MSA but uncontrolled | generic MSA | yes, steered by prompt v2 |

## 4. Measured evidence (methodology stated)

**Method.** The real Marian encoder/decoder ONNX files and tokenizer bundled
with the app were run in ONNX Runtime (CPU) through a Python port of the app's
own SentencePiece unigram tokenizer, greedy decode, and post-engine pipeline
(`tools/marian_eval.py`, output committed to the PR discussion). "Before" is
raw model output; "after" is raw output + whole-line idiom replacement +
quality gate. No device claims: this is a desktop CPU measurement of the same
model files and the same pipeline code paths; device latency will differ.

**The 8 mandatory cases, measured:**

| # | Raw Marian (before) | After pipeline | Fixed here? |
|---|---|---|---|
| 1 | نعم، أراك في الجوار | نعم، أراك في الجوار | not on Marian (line is not exactly the idiom; hint path serves prompt engines) |
| 2 | يا رجل جيد، أنت ابتعد عن المشاكل، حسناً؟ | same | improved for prompt engines via hints |
| 3 | الجيش سيكون خارج القوة قريباً | same | improved via hints + glossary |
| 4 | الحقيقة، ليس لديّ أدنى فكرة أين يختبئ | same | raw was already near-correct |
| 5 | حسناً، ماذا أخبرتها؟ | same (untouched, correct) | regression guard holds |
| 6 | هل تعتقد أنها ستخبرني؟ | **أتظن أنها ستخبرني؟** (exact-line match + question-mark restore, `+idiom +qc`) | YES, measured |
| 7 | ما الذي تحتاجه (الفايرفايرس) مع (روبرت)؟ | same | glossary pins اليراعات for prompt engines and dictionary paths |
| 8 | (مارلين)، كانت تسأل في الأرجاء، تحاول إيجاده. | same | "ask around" hint serves prompt engines |

**Accuracy improvement, honestly stated.** On Marian, the post-engine pipeline
measurably fixes 1 of the 8 mandatory lines and regresses none; the other
seven are addressed at the knowledge layer (dataset + glossary + hints), whose
correctness is enforced by 123 corpus tests but whose final rendering depends
on the instruction-following engine, which cannot run on the JVM or this CI.
We therefore claim: detection and required knowledge = 100% of the 8 cases
(test-enforced); final rendered quality on prompt engines = verified by
inspection of the prompt content, not by end-to-end device runs. No fake
numbers.

**Latency.** Engine (this CPU): 320–780 ms/line, unchanged by the fix (no new
model calls). Added post-processing: 27–225 µs/line, i.e. under 0.1% of engine
time. Understanding analysis over the whole 123-line corpus: microseconds per
line (test-enforced ceiling). No retranslation loops exist by construction.

**Memory.** Added resident data: ~70 understanding entries (~30 KB), glossary
v3 additions (~15 KB), context memory bounded at the current scene's recent
lines. Models unchanged; no new downloads.

## 5. MSA quality rules

Prompt v2 requires contemporary MSA (no archaic constructions), preservation
of the source tense, question form and negation, and no padding (no added
honorifics or connective filler). Profanity policy is unchanged across every
engine and fallback: preserved, never amplified, never censored.

## 6. Unsolved problems (stated plainly)

- Marian and ML Kit remain single-line literal engines; multi-word idioms
  embedded in longer lines reach them only through the knowledge layer's
  effect on caching/prompts of other engines. Whole-line replacement covers
  lines that ARE one idiom.
- Understanding coverage is a finite seeded dataset (70 entries + glossary);
  unseen idioms still translate literally until added (user terms help for
  glossary-class terms, not idioms).
- The quality gate detects lost negation but, by design, never rewrites it —
  surfacing the issue tag is the current behavior.
- Scene separation is time-gap based (60 s); a game that pauses mid-scene
  longer than that starts a new scene.
- End-to-end rendering quality of the Gemma path needs an on-device run; that
  was not performed here and is not claimed.
