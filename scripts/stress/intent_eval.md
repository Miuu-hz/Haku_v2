# P5.3 intent eval (manual, adb, debug build)

Chat evals cannot see notifications, so this one is by hand. Pass bar (plan P5.3): 10 intentions, >80% fire on cue,
none fires off-condition or past its quota. The same bar runs in CI as `IntentTest` "ten intentions over a week".

## 1. Create intentions (same path as the chat box)

```sh
A="adb shell am broadcast -n com.bbpillow.haku/.AskDebugReceiver -a com.bbpillow.haku.debug.ASK --es q"
$A "'ถ้าไปถึงที่ทำงานให้เตือนส่งรายงาน'"
$A "'ถ้ามีประชุมทีมให้เตือนเตรียมสไลด์'"
$A "'ถ้าจดเรื่องกาแฟให้บอกว่าลดน้ำตาล 2 ครั้ง'"
$A "'ถ้าเจอคำว่าวันเกิด เตือนซื้อของขวัญ'"
$A "'เมื่อวานไปไหนมา'"            # control: must answer normally, not "จะเตือน"
adb logcat -d -s HakuAsk | tail -5   # answer = "จะเตือน “…” เมื่อ… (ครั้งเดียว ภายใน 30 วัน)"
adb shell run-as com.bbpillow.haku cat files/memory/INTENTIONS.md   # เมื่อ:/เตือน:/กติกา: fired=0
```

The app: สิ่งที่ Haku จำ › ความตั้งใจค้างไว้ lists them; ยกเลิก removes one from the file.

## 2. Fire each kind

| Trigger | How | Expect |
|---|---|---|
| note | Timeline: edit a line to "ร้านกาแฟ" (or a quick note "กาแฟเย็น") | notification "อย่าลืม: ลดน้ำตาล" on channel เตือนตามเงื่อนไข |
| note again | edit another line with "กาแฟ" within 6 h | nothing (cooldown); after 6 h fires once more, then never (max 2) |
| appointment | add a calendar event "ประชุมทีม" later today, then any capture wake-up (unlock/charge) | "อย่าลืม: เตรียมสไลด์" once; re-reads do not repeat |
| place | arrive at the confirmed work place (real walk, or capture START + stay still) | "อย่าลืม: ส่งรายงาน" once |
| misfire | notes/appointments without the words ("ประชุม" alone, "ตลาด") | nothing |

After each step `run-as … cat files/memory/INTENTIONS.md`: `fired=` goes up by exactly one per notification and never
past `max=`. Lock screen shows only "Haku มีเรื่องเตือนตามที่คุณตั้งไว้". Failures log to `adb logcat -s HakuIntent`.
