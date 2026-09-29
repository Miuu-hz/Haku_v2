# /// script
# requires-python = ">=3.12"
# dependencies = ["huggingface_hub>=0.25", "soundfile>=0.12"]
# ///
"""Fetch ~25 min of FLEURS Thai (th_th) test audio + gold transcripts.

Source: huggingface.co/datasets/google/fleurs (CC-BY 4.0). Downloads the raw
test.tsv + audio/test.tar.gz via huggingface_hub (no dataset loading script),
picks one recording per sentence (distinct sentences, deterministic order)
until the target duration is reached, and extracts only those WAVs,
converted from float32 to 16 kHz mono 16-bit PCM (same format as own recordings).

Usage: uv run --python 3.12 fetch_fleurs_th.py [--out DIR] [--minutes 25]
"""
import argparse
import csv
import io
import tarfile
from pathlib import Path

import soundfile as sf
from huggingface_hub import hf_hub_download

REPO = "google/fleurs"
SAMPLE_RATE = 16_000  # FLEURS num_samples column is at 16 kHz
DEFAULT_OUT = Path("F:/Haku Project/data/asr-th/fleurs")


def select(tsv_path: Path, target_s: float) -> list[tuple[str, float, str]]:
    """One recording per sentence id, in file order, until target_s is reached."""
    seen, picked, total = set(), [], 0.0
    with open(tsv_path, encoding="utf-8", newline="") as f:
        for row in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            sent_id, wav, raw_text, dur = row[0], row[1], row[2], int(row[5]) / SAMPLE_RATE
            if sent_id in seen:
                continue
            seen.add(sent_id)
            picked.append((wav, round(dur, 2), raw_text.strip()))
            total += dur
            if total >= target_s:
                break
    return picked


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--minutes", type=float, default=25.0)
    args = ap.parse_args()

    cache = args.out.parent / ".hf-cache"  # outside the repo, reused on re-run
    dl = dict(repo_id=REPO, repo_type="dataset", cache_dir=cache)
    tsv = Path(hf_hub_download(filename="data/th_th/test.tsv", **dl))
    picked = select(tsv, args.minutes * 60)
    wanted = {name for name, _, _ in picked}

    tar_path = Path(hf_hub_download(filename="data/th_th/audio/test.tar.gz", **dl))
    wav_dir = args.out / "wav"
    wav_dir.mkdir(parents=True, exist_ok=True)
    found = set()
    with tarfile.open(tar_path, "r:gz") as tar:
        for m in tar:
            name = Path(m.name).name
            if m.isfile() and name in wanted:
                audio, sr = sf.read(io.BytesIO(tar.extractfile(m).read()))
                if sr != SAMPLE_RATE or audio.ndim != 1:
                    raise SystemExit(f"{name}: expected 16 kHz mono, got {sr} Hz shape {audio.shape}")
                sf.write(wav_dir / name, audio, sr, subtype="PCM_16")
                found.add(name)
    missing = wanted - found
    if missing:
        raise SystemExit(f"{len(missing)} selected files not in tarball, e.g. {sorted(missing)[:3]}")

    manifest = args.out / "manifest.csv"
    with open(manifest, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["file", "duration_s", "reference_text"])
        for name, dur, text in picked:
            w.writerow([f"wav/{name}", dur, text])
    total_min = sum(d for _, d, _ in picked) / 60
    print(f"{len(picked)} utterances, {total_min:.1f} min -> {manifest}")


if __name__ == "__main__":
    main()
