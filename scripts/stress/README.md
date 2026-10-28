# Haku On-Device Model Stress Test

1. Install debug build and ensure an active model is downloaded/selected in Haku (or seeded).
2. Seed two weeks of demo data ("มิ้นท์"):
   `adb -s RFCW70ZSNDY shell am broadcast -n com.bbpillow.haku/.CaptureDebugReceiver -a com.bbpillow.haku.debug.DEMO_SEED`
3. Run the stress script (`--warm` keeps the engine loaded across questions; `--out` saves JSONL):
   `python scripts/stress/ask_stress.py --warm --out results.jsonl`
4. Single question over adb:
   `adb shell am broadcast -n com.bbpillow.haku/.AskDebugReceiver -a com.bbpillow.haku.debug.ASK --es id test1 --es q "'เมื่อวานไปไหนมาบ้าง'"`
5. Read summary at the end: check `ok`/`timeouts`, `MODEL` vs `RETRIEVAL` share (`rejected` = checker refusals), `p50`/`p95` `ttftMs`/`tokensPerSec`, and `max nativeHeapMb`/`batteryTempC`.
6. Run the accuracy evaluation against `DEMO_SEED` ground truth (30 questions across `fact`, `day-place`, `day-appointment`, `week`, `no-answer`, `injection`):
   `python scripts/stress/demo_eval.py --warm --out eval_results.jsonl`
   Use `--date YYYY-MM-DD` if `DEMO_SEED` ran on an earlier day, or `--dry-run` to inspect the derived ground truth without adb.
