#!/usr/bin/env python3
"""Honest before/after evidence for the deep-engine fix, using the REAL
shipped Marian EN->AR models (/tmp/marian/*.onnx) and a Python port of the
app's SentencePieceTokenizer (unigram Viterbi, metaspace) and of the
coordinator's post-engine steps (exactLineRendering + quality gate).

before = raw Marian greedy decode (what shipped versions produce, once the
         28-input decoder contract fixed in the audit branch is honored)
after  = before + exact-line idiom replacement + quality-gate corrections
"""
import json, time, sys
import numpy as np
import onnxruntime as ort

TOK = json.load(open('/tmp/marian/tokenizer.json'))
UNK, EOS, PAD, DSTART = TOK['unk_id'], TOK['eos_id'], TOK['pad_id'], TOK['decoder_start_id']
PIECES = [p for p, s, i in TOK['enc']]
SCORES = [float(s) for p, s, i in TOK['enc']]
MIDS = [int(i) for p, s, i in TOK['enc']]
DEC = TOK['dec']
BYFIRST = {}
for i, p in enumerate(PIECES):
    if p:
        BYFIRST.setdefault(p[0], []).append(i)
METASPACE = '▁'
MAXPIECE = 32
UNK_SCORE = -20.0

def normalize(t):
    t = ' '.join(t.strip().split())
    return METASPACE + t.replace(' ', METASPACE)

def viterbi(text):
    n = len(text)
    best = [float('-inf')] * (n + 1)
    back = [-1] * (n + 1)
    best[0] = 0.0
    for i in range(n):
        if best[i] == float('-inf'):
            continue
        matched = False
        for pi in BYFIRST.get(text[i], ()):
            piece = PIECES[pi]
            if len(piece) > MAXPIECE:
                continue
            if text.startswith(piece, i):
                matched = True
                e = i + len(piece)
                sc = best[i] + SCORES[pi]
                if sc > best[e]:
                    best[e] = sc
                    back[e] = pi
        if not matched:
            sc = best[i] + UNK_SCORE
            if sc > best[i + 1]:
                best[i + 1] = sc
                back[i + 1] = -10**9
    if best[n] == float('-inf'):
        return [UNK]
    out = []
    e = n
    while e > 0:
        b = back[e]
        if b == -10**9:
            out.append(UNK); e -= 1
        else:
            out.append(MIDS[b]); e -= len(PIECES[b])
    return out[::-1]

def encode(t):
    return viterbi(normalize(t)) + [EOS]

def decode(ids):
    sb = []
    for i in ids:
        if i in (EOS, PAD):
            break
        piece = DEC[i] if i < len(DEC) else None
        if piece is None or piece == '<s>':
            continue
        if piece == '<unk>':
            sb.append('⁇')
        else:
            sb.append(piece)
    return ''.join(sb).replace(METASPACE, ' ').strip()

enc = ort.InferenceSession('/tmp/marian/encoder.onnx', providers=['CPUExecutionProvider'])
dec = ort.InferenceSession('/tmp/marian/decoder.onnx', providers=['CPUExecutionProvider'])
NLAYERS = 6
DEC_IN = [i.name for i in dec.get_inputs()]

def greedy(src_ids, max_tokens=128):
    ids = np.array([src_ids], dtype=np.int64)
    mask = np.ones_like(ids)
    hs = enc.run(['last_hidden_state'], {'input_ids': ids, 'attention_mask': mask})[0]
    # step 0: no past
    src_len = ids.shape[1]
    past = {}
    for l in range(NLAYERS):
        past[f"past_key_values.{l}.decoder.key"] = np.zeros((1, 8, 1, 64), dtype=np.float32)
        past[f"past_key_values.{l}.decoder.value"] = np.zeros((1, 8, 1, 64), dtype=np.float32)
        # zero-length encoder past breaks a Reshape in this ORT build; the
        # no-cache branch ignores values, so pass correctly-shaped zeros.
        past[f'past_key_values.{l}.encoder.key'] = np.zeros((1, 8, src_len, 64), dtype=np.float32)
        past[f'past_key_values.{l}.encoder.value'] = np.zeros((1, 8, src_len, 64), dtype=np.float32)
    prefix = [DSTART]
    use_cache = False
    for step in range(max_tokens):
        feed = dict(past)
        feed['encoder_attention_mask'] = mask
        feed['input_ids'] = np.array([[prefix[-1]]] if use_cache else [prefix], dtype=np.int64)
        feed['encoder_hidden_states'] = hs
        feed['use_cache_branch'] = np.array([use_cache])
        outs = dec.run(None, feed)
        logits = outs[0]
        nxt = int(np.argmax(logits[0, -1]))
        if nxt == EOS:
            break
        prefix.append(nxt)
        # The app runs the decoder NO-CACHE every step (empty pasts +
        # use_cache_branch=false, full prefix) because this export's cache
        # branch cannot cold-start; replicate that contract exactly.
        use_cache = False
    return prefix[1:]

# Post-engine pipeline replicated from the app:
DS = json.load(open('/tmp/glm-test/app/src/main/assets/understanding/english_expressions.json'))
EXPR = {}
for layer in ('idioms', 'phrasal_verbs', 'informal', 'dialogue_expressions'):
    for e in DS[layer]:
        EXPR[e['en'].lower()] = e

def exact_line_rendering(source):
    norm = source.strip().lower().rstrip('.!?…,').strip()
    if not norm:
        return None
    e = EXPR.get(norm)
    if not e:
        return None
    return e['ar_msa'] or None

AR_NEG = ['لا', 'لم', 'لن', 'ليس', 'ليست', 'مش', 'ما', 'بدون', 'دون', 'غير', 'مستحيل', 'أبدًا', 'ابدًا']
import re
EN_NEG = re.compile(r"\b(not|n't|no|never|nothing|nobody|nowhere|ain't|cannot|can't|won't|don't|doesn't|didn't|isn't|aren't|wasn't|weren't|haven't|hasn't|hadn't|nah|nope)\b", re.I)

def has_arabic_negation(t):
    words = re.findall(r'[\u0600-\u06FF]+', t)
    return any(w in AR_NEG for w in words)

def quality_gate(source, text):
    issues, corrected = [], False
    out = text
    if source.rstrip().endswith('?') and not (out.rstrip().endswith('؟') or out.rstrip().endswith('?')):
        issues.append('QUESTION_MARK_LOST')
        out = out.rstrip().rstrip('.。') + '؟'
        corrected = True
    if EN_NEG.search(source) and not has_arabic_negation(out):
        issues.append('NEGATION_LOST')
    if out.strip() == source.strip():
        issues.append('UNTRANSLATED')
    return out, issues, corrected

CASES = [
    ('1', 'Yeah, see you around.'),
    ('2', 'Good man. Hey, you stay out of trouble, alright?'),
    ('3', "Military's gonna be out in force soon."),
    ('4', "The truth. I got no idea where he's hiding."),
    ('5', 'Well, what did you tell her?'),
    ('6', "You think she'd tell me?"),
    ('7', 'Marlene? What do the Fireflies need with Robert?'),
    ('8', "Uh... Marlene. She's been asking around, trying to find him."),
]

results = []
for cid, src in CASES:
    t0 = time.perf_counter()
    raw = decode(greedy(encode(src)))
    t1 = time.perf_counter()
    fixed = exact_line_rendering(src)
    idiom_applied = fixed is not None and fixed != raw
    after_idiom = fixed if fixed else raw
    gated, issues, corrected = quality_gate(src, after_idiom)
    t2 = time.perf_counter()
    results.append({
        'case': cid, 'source': src, 'raw_marian': raw,
        'idiom_replacement': fixed if idiom_applied else None,
        'gate_issues': issues, 'gate_corrected': corrected,
        'after': gated,
        'engine_ms': round((t1 - t0) * 1000, 1),
        'postprocess_us': round((t2 - t1) * 1e6, 1),
    })

json.dump(results, open('/tmp/marian/eval_results.json', 'w'), ensure_ascii=False, indent=2)
for r in results:
    print(f"--- case {r['case']}: {r['source']}")
    print(f"  raw   : {r['raw_marian']}")
    print(f"  after : {r['after']}  (idiom={bool(r['idiom_replacement'])}, issues={r['gate_issues']})")
    print(f"  engine {r['engine_ms']}ms, postprocess {r['postprocess_us']}us")
