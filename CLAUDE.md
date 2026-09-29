# Haku v2

ระบบบันทึกชีวิตอัตโนมัติ AI ทำงานในมือถือทั้งหมด Android ก่อน (Kotlin Multiplatform บนโครง Google AI Edge Gallery)

## แผนอยู่ที่ไหน
- `F:\Haku Project\Haku-v2-Plan\01-แนวทางและหลักคิด.docx` — หลักคิด สถาปัตยกรรม การตัดสินใจ
- `F:\Haku Project\Haku-v2-Plan\02-แผนพัฒนารายเฟส.md` — ทำตาม "ลำดับการทำงานจริง" 7 ช่วง แต่ละเฟสมีเกณฑ์ผ่าน
- `F:\Haku Project\Haku-v2-Plan\03-กลยุทธ์ภาษาไทย.md` — อ่านก่อนเฟสที่แตะภาษาไทย
- `docs/decisions.md` — บันทึกการตัดสินใจ D1–D23 (P0.1)

## แหล่งอ้างอิงโค้ด
- `F:\Haku Project\gallery-main` — แกน agent/tool/skill และตัวรันโมเดล (Apache 2.0, เก็บ license header)
- `F:\Haku Project\haku` — v1 (Flutter) อ่านเพื่อเข้าใจกติกา แล้วเขียนใหม่พร้อมเทสต์ ห้ามคัดลอกทั้งไฟล์ ยกเว้นโค้ด Kotlin ที่แตะระบบปฏิบัติการตรง
- `F:\Haku Project\openclaw-main` — แบบของระบบความจำ

## กติกาโค้ด (ทำให้ graphify เห็นการไหลของข้อมูล)
กราฟโค้ดสร้างจาก AST อัตโนมัติทุก commit มันเห็นแค่ import/call/type จึงต้องเขียนให้การเชื่อมฐานข้อมูลและ input/output เป็นชื่อที่มองเห็นได้:

1. **แพ็กเกจตามชั้นของสถาปัตยกรรม** `capture` · `memory` · `thinking` · `action` · `surface` · `network` · `platform` — กลุ่มในกราฟจะตรงกับชั้น ข้อยกเว้นเดียวคือ `language` (ชั้นประมวลผลข้อความ P0.5) ที่ทุกชั้นใช้ร่วม
2. **ฐานข้อมูลแตะได้ที่เดียว** SQL และ DAO อยู่ในคลาสชื่อลงท้าย `Store` เท่านั้น ส่วนอื่นเรียกผ่าน interface ชื่อลงท้าย `Repository` — edge ที่วิ่งเข้า `*Repository`/`*Store` = การอ่านเขียนฐานข้อมูล
3. **ขอบเขตระหว่างโมดูลส่งเป็นชนิดข้อมูลที่ตั้งชื่อ** `*Input` · `*Output` · `*Event` · `*Record` เป็น data class ไม่ส่ง `Map`/`String` ดิบ — edge ที่อ้างชนิดเหล่านี้ = input/output ของฟังก์ชัน
4. **ระบบปฏิบัติการอยู่หลัง interface ใน `platform`** แกนกลางไม่ import `android.*`

## วินัย
- เทสต์มาพร้อมโค้ด แกนกลางครอบคลุมเกิน 80%
- ส่งโค้ดทุกวันที่ทำงาน commit แบบ conventional (`feat:`, `fix:`, `docs:` …)
- จบแต่ละช่วงตรวจ PDPA ด้วย skill `pdpa-advisor`
- จบแต่ละเฟสรัน `/graphify . --update` เพื่อเติมความสัมพันธ์เชิงความหมาย (ส่วน AST อัปเดตเองทุก commit)

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
