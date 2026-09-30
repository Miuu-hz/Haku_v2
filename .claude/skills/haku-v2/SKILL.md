---
name: haku-v2
description: >
  Context map of the Haku v2 codebase (on-device Thai life-logging app, Kotlin Multiplatform,
  Android first). Load at the start of any Haku v2 work session, before reading code, to know
  where data flows (capture → memory → index → thinking → surface), which file owns what, and
  which rules are non-negotiable. Triggers: "haku", "haku v2", "ไดอารี", "Haku_v2", work in
  F:\Haku Project\haku-v2.
---

# Haku v2 — แผนที่โค้ด (อ่านก่อนเริ่มงาน)

> ไฟล์นี้คือ **ดัชนี ไม่ใช่สำเนาโค้ด** เก็บแค่ "ข้อมูลไหลจากไหนไปไหน ผ่านการตรวจอะไร"
> รายละเอียดระดับฟังก์ชันให้ถามกราฟ: `graphify query "<คำถาม>"` / `graphify explain "<ชื่อคลาส>"` (อัปเดตเองทุก commit)
> แผน: `F:\Haku Project\Haku-v2-Plan\02-แผนพัฒนารายเฟส.md` (ลำดับจริง 7 ช่วง) · สถานะ: `docs/CHECKLIST.md` · รอผู้พัฒนา: `docs/FOR-YOU.md` · การตัดสินใจ: `docs/decisions.md`

## โมดูล
`core/` ตรรกะล้วน KMP (ห้าม android.*) · `ui/` Compose MP หน้าจอแบบ state-hoisted · `androidApp/` ตัวเชื่อม OS (`platform/`) · `src/debug/` เครื่องมือทดสอบ (ไม่อยู่ใน release)
แพ็กเกจ = ชั้น: `capture` `memory` `thinking` `surface` `language` `platform` (`action` `network` ยังว่าง)

## การไหลหลัก (อ่านจากบนลงล่าง)

```
[1 จับ]  OS wake-up (activity transition · geofence exit · ชาร์จ/ปลดล็อก · ปฏิทิน)
   androidApp/platform/capture|signals  ──► core/capture/DwellDetector (ตัดสิน: นิ่ง+พิกัด=เริ่มอยู่ · ห่าง>150m=ออก)
   ──► CaptureRecorder ──► CaptureEventStore (SQLite `events`, id คงที่ `dwell-start-<ms>`, ลบ=tombstone ไม่กลับมา)

[2 สถานที่] CaptureEvent(dwell) ──► PlaceRecognizer: pair → merge (≤10นาที/150m) → ตัด <15นาที → cluster 100m
   → เดาบ้าน(22–06)/งาน(จ–ศ 09–17) ≥3วัน ──► PlaceStore (ยืนยันครั้งเดียว `confirmed=1`)

[3 ไดอารี] events+places ──► capture/diary/EpisodeBuilder (ออก/ถึง/นัด) ──► DiaryRenderer+DiaryTemplates (แม่แบบไทย D14,
   คำลงท้าย D15, เวลาไว้หน้า) ──► ตรวจ DiaryLedgerStore (ลบแล้ว/ลบประเภทนี้ครบ 3 ครั้ง → ไม่เขียน)
   ──► MemoryRepository.appendDaily (provenance=INFERRED_FROM_USER_DATA) · ชื่อสถานที่เฉพาะที่ยืนยันแล้ว ไม่งั้น "สถานที่หนึ่ง"

[4 ความจำ] memory/MemoryStore = ไฟล์ Markdown ที่เปิดอ่านได้ (okio, atomic write):
   INSTRUCTIONS.md · INTENTIONS.md · core/<you|topics|areas|projects|…>.md · daily/yyyy-MM-dd.md (วัน UTC!) · dreams/
   ด่านเขียน (P1.7): EXTERNAL/SYSTEM ห้ามเข้า core/standing ทุกทาง (write, promote, rebuild) · ระยะยาวไม่หมดอายุ ลบด้วยมือผู้ใช้เท่านั้น

[5 ดัชนี] IndexedMemoryRepository (decorator) ── ทุก write/delete ──► ไฟล์ก่อน แล้ว SearchIndexStore
   (SQLite FTS5 บนสำเนาที่ normalize+ตัดคำ, ต้นฉบับไม่แตะ) · ไฟล์คือความจริง: MemoryIndexer.rebuild()/resync()

[6 ภาษา] ทุกข้อความก่อนเก็บดัชนี/เทียบ: language/TextPipeline = Normalizer(D13, กฎไทยใน ThaiRules ไฟล์เดียว)
   → ScriptSplitter → WordSegmenter(Android: IcuWordSegmenter) → ขยายไม้ยมก · คำพ้อง ThaiSynonyms (ร่าง)

[7 คิด] คำถาม ──► thinking/context/ContextAssembler: standing(เสมอ) → หมวด core ที่เกี่ยว(≤2) → daily ตามคำบอกวัน(TimeWords)
   → FTS → [semantic hook ปิด] → [link hook] · ตัดของค่าน้อยจนไม่เกินงบ (TokenEstimator) · ทุกบรรทัดมี source+provenance
   โมเดล: thinking/LocalModel (stateless, สร้าง conversation ใหม่ทุกครั้ง) ← LocalModelClient วัด load/TTFT/tok/s
          thinking/AgentSession (มีประวัติจน reset) ← androidApp/platform/agent/LiteRtAgentSession (+ ToolDefinition.runTool = ทางเดียวของเครื่องมือ)

[8 สรุปเย็น] diary lines ──► thinking/summary/SummaryPromptBuilder (≤900 token) ──► LocalModel ──► SummaryChecker
   (ห้ามเวลา/ตัวเลข/สถานที่/คำพูดที่ไม่มีในข้อเท็จจริง · คำลงท้ายต้องตรง) ── ผ่าน→ MODEL (เก็บครั้งเดียวต่อวัน) / ไม่ผ่าน→ SummaryTemplate

[8b กลางคืน] androidApp/platform/NightlyWorker (WorkManager: ชาร์จ+idle, ทุก 3 ชม.) ── core/thinking/summary/NightlyPlan ตัดสิน
   → เรียบเรียงไดอารี เมื่อวาน+วันนี้ → สรุปเย็น (ถ้ามีไฟล์โมเดล; ปล่อย engine หลังใช้) → memory/dream/DreamEngine
   Dream: จัดระเบียบ(ซ้ำตรงตัว, ยกเลิกถ้าลบ>30%) → เรื่องซ้ำด้วยกฎ(สถานที่/นัด/โน้ต ≥3 วัน) → ด่าน(ที่มาถาวรได้ · ไม่เคยถูกลืม/ปฏิเสธ
   · คะแนน≥0.3 · ≤5 ต่อคืน) → MemoryRepository.promote → รายงานไทยใน dreams/<day> (ledger ที่วันสงวน 0000-00-00)

[8c สถิติ] capture/stats/StatsCalculator (ตัวเลขที่วัดได้เท่านั้น, null = ไม่แสดง) → StatsSummarizer + Entitlements (FREE=สัปดาห์นี้,
   PLUS=เดือน/ปี/เทียบ) → StatsTemplates ไทย → การ์ดหน้าแรก

[8e Plus] androidApp/platform/billing/PlayBilling (Play Billing: restore ตอนเปิด · ซื้อ · acknowledge) → surface/plus/EntitlementRules
   (PLUS ถ้าตรวจกับ Play ล่าสุด <7 วัน ไม่งั้น FREE ข้อมูลไม่แตะ) → EntitlementStore `filesDir/entitlement.txt` (token เก็บแค่ SHA-256)
   → StateFlow<Entitlements> → AppGraph.entitlements (ด่านสถิติ) · ราคาจาก Play เท่านั้น · กลุ่มราคา A/B จากรหัสติดตั้ง

[8d ลืม] Timeline ลบ/แก้ → tombstone + ทิ้งสรุปเย็นวันนั้น · Transparency: ลืมข้อ/หมวด/ทุกอย่าง (surface/memory/ForgetEverything:
   tombstone event ทุกตัวแล้วลบ, places, ledger, summaries, memory+index) · ส่งออก MemoryExport.markdown (ผู้ใช้กดเอง ไม่นับเป็นข้อมูลออก)

[9 หน้าจอ] androidApp/AppGraph (DI มือ, DB เดียว noBackupFilesDir/haku.db) → MainActivity: ยังไม่เริ่มใช้ → Onboarding / ล็อก → MainShell 3 แท็บ
   core/surface/*Presenter (แปลง repo → UiState, ไม่มี android) ──► ui/surface/*Screen (รับ state + callback เท่านั้น)
   Home(เช้า=เมื่อวาน/กลางวัน/เย็น, ไม่แสดงตัวเลขที่ไม่มีข้อมูล) · Timeline(แก้=USER_TYPED ใหม่ + tombstone เดิม) ·
   Transparency(ลืม) · Onboarding B1–B7 (สิทธิ์เริ่มปิดทุกตัว PDPA ม.19; OS ทำผ่าน OnboardingActions)
   ชิ้นส่วน: ui/surface/components (โทเค็นจาก haku-mockup.html ใน HakuTheme เท่านั้น)
```

## กติกาที่ห้ามพัง
- เวลา: เก็บ UTC epoch ms / แสดงเวลาท้องถิ่นและ พ.ศ. (D17) · ไฟล์ daily เป็นวัน UTC → อ่านวันท้องถิ่นผ่าน `DiaryComposer.readDay(localDay, tz)`
- ลบแล้วต้องไม่กลับมา: capture `deleted_ids` · diary ledger · memory delete — ทุก re-run ต้องเคารพ
- ข้ามโมดูลส่งเป็น `*Input/*Output/*Event/*Record` · SQL อยู่ใน `*Store` เท่านั้น · core ห้าม `android.*`
- ข้อมูลไม่ออกนอกเครื่องโดยค่าเริ่มต้น · เครื่องมือ/สิทธิ์ใหม่ → ตรวจด้วย skill `pdpa-advisor`
- เทสต์: `export JAVA_HOME="/f/Android Studio/jbr"; ./gradlew --no-daemon :core:jvmTest :core:testDebugUnitTest :androidApp:assembleDebug`
  (SQLite เทสต์ใน jvmTest · เครื่องนี้ RAM น้อย: ห้ามเปิด emulator พร้อม build หลายตัว)
- งานคู่ขนาน: git worktree ต่อ subagent ที่ path สั้น `F:\Haku Project\hk-wt-<ชื่อ>` (Windows path ยาวลบไม่ได้) → merge `--no-ff` → ลบ worktree

## ยังไม่มี / รอเครื่องจริง
ผลวัดบน Z Fold5 (ชิ้น ก ข ง · จับข้อมูลหนึ่งวัน · เอเจนต์เรียกเครื่องมือ · สองบทสนทนาบน engine เดียว) · EmbeddingGemma (P1.5) · ชำระเงิน P9.4 กับ Play Console จริง ·
เข้ารหัส DB/ไฟล์ (D11) · ชั้น 4–9 · ดูรายการเต็มใน `docs/CHECKLIST.md`

## วิธีดูแลไฟล์นี้
แก้เมื่อ **ทางไหลของข้อมูลเปลี่ยน** (เพิ่มโมดูล/ด่านตรวจ/ที่เก็บ) ไม่ใช่ทุกครั้งที่แก้โค้ด · หนึ่งกล่อง ≤4 บรรทัด · เกิน ~120 บรรทัดให้ย้ายรายละเอียดไปกราฟ
