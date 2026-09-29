# /// script
# requires-python = ">=3.12"
# dependencies = ["pythainlp>=5.0"]
# ///
"""Thai WER (after PyThaiNLP newmm word segmentation) and CER.

Usage:
  uv run --python 3.12 wer.py REFS.csv HYPS.csv [--details out.csv]
  uv run --python 3.12 wer.py --self-test

REFS.csv: columns file, reference_text (e.g. manifest.csv from fetch_fleurs_th.py)
HYPS.csv: columns file, hypothesis_text (produced by the on-device app)
Rows are joined on `file`. A reference with no hypothesis counts as an empty
hypothesis (all deletions) and is reported. Scores are corpus-level:
total edits / total reference units. Pass criterion: WER < 25%.
"""
import argparse
import csv
import re
import sys
import unicodedata

from pythainlp.tokenize import word_tokenize

PASS_WER = 0.25
ZERO_WIDTH = dict.fromkeys(map(ord, "​‌‍⁠﻿­"))
THAI_DIGITS = str.maketrans("๐๑๒๓๔๕๖๗๘๙", "0123456789")
THAI = "฀-๿"
SPACE_BETWEEN_THAI = re.compile(rf"(?<=[{THAI}])\s+(?=[{THAI}])")


def normalize(text: str) -> str:
    """NFC, drop zero-width, Thai digits->Arabic, lowercase, punctuation->space.

    Spaces between two Thai chars are removed (Thai spacing is stylistic and
    ASR output varies); other spaces stay so Latin words remain separate.
    """
    text = unicodedata.normalize("NFC", text).translate(ZERO_WIDTH).translate(THAI_DIGITS).lower()
    # ponytail: Unicode category P* = punctuation; Thai ๆ (Lm) and ฯ (Lo) are kept as letters
    text = "".join(" " if unicodedata.category(c).startswith("P") else c for c in text)
    text = SPACE_BETWEEN_THAI.sub("", text)
    return " ".join(text.split())


def words(text: str) -> list[str]:
    return word_tokenize(normalize(text), engine="newmm", keep_whitespace=False)


def chars(text: str) -> list[str]:
    return [c for c in normalize(text) if not c.isspace()]


def edits(ref: list[str], hyp: list[str]) -> int:
    """Levenshtein distance (sub/ins/del cost 1)."""
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i]
        for j, h in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h)))
        prev = cur
    return prev[-1]


def score(pairs: list[tuple[str, str]]) -> dict:
    """Corpus-level WER/CER over (reference, hypothesis) pairs."""
    we = wn = ce = cn = 0
    for ref, hyp in pairs:
        rw, rc = words(ref), chars(ref)
        we += edits(rw, words(hyp)); wn += len(rw)
        ce += edits(rc, chars(hyp)); cn += len(rc)
    return {"wer": we / max(wn, 1), "cer": ce / max(cn, 1), "ref_words": wn, "ref_chars": cn}


def read_csv(path: str, col: str) -> dict[str, str]:
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if not {"file", col} <= set(reader.fieldnames or []):
            sys.exit(f"{path}: needs columns 'file' and '{col}', has {reader.fieldnames}")
        return {row["file"]: row[col] or "" for row in reader}


def self_test() -> None:
    # (ref, hyp, hand-segmented ref words, hand-segmented hyp words, expected WER, expected CER)
    cases = [
        # newmm dictionary has compounds (กินข้าว, ดีมาก): one wrong part = one wrong word
        ("ฉันกินข้าว", "ฉันกินข้าว", ["ฉัน", "กินข้าว"], ["ฉัน", "กินข้าว"], 0, 0),
        # normalization only: Thai digits, punctuation, spaces, zero-width -> no error
        ("ปี ๒๕๖๗ ครับ!", "ปี2567​ครับ", ["ปี", "2567", "ครับ"], ["ปี", "2567", "ครับ"], 0, 0),
        ("เปิด Bluetooth ให้หน่อย", "เปิด bluetooth, ให้หน่อย", ["เปิด", "bluetooth", "ให้", "หน่อย"],
         ["เปิด", "bluetooth", "ให้", "หน่อย"], 0, 0),
        # 1 substitution of 2 words; chars ข้าว->น้ำ: ข->น, า->ำ, del ว = 3 of 10
        ("ฉันกินข้าว", "ฉันกินน้ำ", ["ฉัน", "กินข้าว"], ["ฉัน", "กินน้ำ"], 1 / 2, 3 / 10),
        # ดีมาก->ดี = 1 substitution of 3 words; 3 of 16 chars deleted (ม า ก)
        ("วันนี้อากาศดีมาก", "วันนี้อากาศดี", ["วันนี้", "อากาศ", "ดีมาก"], ["วันนี้", "อากาศ", "ดี"], 1 / 3, 3 / 16),
        # 1 insertion over 2 words; 4 inserted chars (แ ล ้ ว) over 10
        ("ไปโรงเรียน", "ไปโรงเรียนแล้ว", ["ไป", "โรงเรียน"], ["ไป", "โรงเรียน", "แล้ว"], 1 / 2, 4 / 10),
    ]
    ok = True
    for ref, hyp, rw, hw, want_wer, want_cer in cases:
        got_rw, got_hw, s = words(ref), words(hyp), score([(ref, hyp)])
        good = (got_rw, got_hw) == (rw, hw) and abs(s["wer"] - want_wer) < 1e-9 and abs(s["cer"] - want_cer) < 1e-9
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  WER {s['wer']:.3f} (want {want_wer:.3f})  "
              f"CER {s['cer']:.3f} (want {want_cer:.3f})  {got_rw} | {got_hw}")
    # corpus-level: edits summed over all cases = 0+0+0+1+1+1 = 3 words / (2+3+4+2+3+2)=16
    s = score([(c[0], c[1]) for c in cases])
    good = abs(s["wer"] - 3 / 16) < 1e-9
    ok &= good
    print(f"{'PASS' if good else 'FAIL'}  corpus WER {s['wer']:.4f} (want {3 / 16:.4f})")
    sys.exit(0 if ok else 1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("refs", nargs="?")
    ap.add_argument("hyps", nargs="?")
    ap.add_argument("--details", help="write per-utterance WER/CER CSV here")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        self_test()
    if not (args.refs and args.hyps):
        ap.error("REFS and HYPS are required (or --self-test)")

    refs, hyps = read_csv(args.refs, "reference_text"), read_csv(args.hyps, "hypothesis_text")
    missing = [f for f in refs if f not in hyps]
    extra = [f for f in hyps if f not in refs]
    if missing:
        print(f"WARNING: {len(missing)} references have no hypothesis (scored as empty), e.g. {missing[:3]}")
    if extra:
        print(f"WARNING: {len(extra)} hypotheses have no reference (ignored), e.g. {extra[:3]}")

    pairs = [(refs[f], hyps.get(f, "")) for f in refs]
    if args.details:
        with open(args.details, "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(["file", "wer", "cer", "ref_words", "hyp_words"])
            for name, (ref, hyp) in zip(refs, pairs):
                s = score([(ref, hyp)])
                w.writerow([name, f"{s['wer']:.4f}", f"{s['cer']:.4f}", "|".join(words(ref)), "|".join(words(hyp))])

    s = score(pairs)
    verdict = "PASS" if s["wer"] < PASS_WER else "FAIL"
    print(f"utterances {len(pairs)}  ref words {s['ref_words']}  ref chars {s['ref_chars']}")
    print(f"WER {s['wer']:.2%}  CER {s['cer']:.2%}  -> {verdict} (criterion WER < {PASS_WER:.0%})")


if __name__ == "__main__":
    main()
