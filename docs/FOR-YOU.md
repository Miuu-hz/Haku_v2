# สิ่งที่รอคุณ (อ่านตอนตื่น)

> Claude เขียนไฟล์นี้ระหว่างทำงานคืนนี้ เรียงจากสำคัญที่สุด

## 1. ทดสอบชิ้น ก บน Z Fold5 (~20 นาที) — สำคัญที่สุด
เป็นเกณฑ์ผ่านที่ขวางทั้งแผน (ถ้าไม่ผ่านต้องทบทวน D1 D3) โมเดล Gemma 4 E2B ดาวน์โหลดไว้แล้วที่ `F:\Haku Project\models\gemma-4-E2B-it.litertlm` (2.4 GB)

เตรียมเครื่อง: เปิด Developer options → USB debugging แล้วเสียบสาย จากนั้นใน Git Bash:
```bash
export JAVA_HOME="/f/Android Studio/jbr"; export PATH="$PATH:/f/AndroidSDK/platform-tools"
cd "/f/Haku Project/haku-v2"
adb devices        # ถ้ามี emulator ด้วย ให้ใส่ -s <serial ของ Fold5> ทุกคำสั่ง adb
adb shell mkdir -p /data/local/tmp/haku
adb push "F:/Haku Project/models/gemma-4-E2B-it.litertlm" /data/local/tmp/haku/
./gradlew --no-daemon :androidApp:installDebug

# GPU (รัน 2-3 รอบ รอบแรกมีการอุ่นเครื่อง)
adb logcat -c
MSYS_NO_PATHCONV=1 adb shell am start -S -n com.bbpillow.haku/.SpikeActivity --es model /data/local/tmp/haku/gemma-4-E2B-it.litertlm --es backend gpu
adb logcat -s HakuSpike:I          # รอจนขึ้น DONE หรือ ERROR แล้วกด Ctrl+C

# CPU
adb shell am force-stop com.bbpillow.haku; adb logcat -c
MSYS_NO_PATHCONV=1 adb shell am start -S -n com.bbpillow.haku/.SpikeActivity --es model /data/local/tmp/haku/gemma-4-E2B-it.litertlm --es backend cpu
adb logcat -s HakuSpike:I
```
อ่านค่า: `LOAD ms` เวลาโหลด · `ttftMs` เวลาโทเค็นแรก · `tokPerSec` ความเร็ว · `pssMB` หน่วยความจำ
**ส่งผลลัพธ์ใน logcat ให้ Claude** แล้วจะบันทึกลง `docs/spikes/` ให้ ผ่านเมื่อ: มีคำตอบภาษาไทยขึ้นบนเครื่อง และวัด TTFT ได้

หมายเหตุ: บน emulator ทดสอบได้แค่ว่า native library โหลดได้ (ผ่านแล้ว) เพราะ emulator มี RAM 2 GB ไม่พอสำหรับโมเดล

## 2. ตรวจ interface ของชิ้น ก (~10 นาที)
ไฟล์ `core/src/commonMain/kotlin/com/bbpillow/haku/thinking/LocalModel.kt`
- `LocalModel`: `load(ModelConfig)` · `generate(GenerateInput): Flow<String>` · `cancel()` · `unload()`
- `LocalModelClient` ครอบทุกตัวรัน วัดเวลาโหลด เวลาโทเค็นแรก และโทเค็นต่อวินาทีให้เอง
- ข้อสังเกต: "โทเค็นต่อวินาที" นับเป็นชิ้นข้อความที่ตัวรันส่งมา ซึ่งใกล้เคียงหนึ่งโทเค็นต่อชิ้นแต่ไม่ใช่ตัวนับโทเค็นจริง

## 3. ตรวจหน้าตา UI (~5 นาที)
- ภาพ: `docs/screens/showcase-home-brief.png` · `showcase-kit-1.png` · `showcase-kit-2.png`
- หรือเปิดเอง: `adb shell am start -n com.bbpillow.haku/.ShowcaseActivity`
- ที่ยังต่างจาก mockup: ไอคอน 3D ยังเป็นไอคอนเส้นบนพื้น gradient · เงาและแสงด้านในเป็นค่าประมาณ · มือถือจริงสูงกว่ากรอบ mockup จึงมีที่ว่างก่อนแถบนำทาง

## 4. ต้องตัดสินใจ
- เลือกบริการฟอร์มลงชื่อรอใช้งาน (Tally / Google Forms) แล้วใส่ action ใน `web/waitlist/index.html` และอีเมลติดต่อสำหรับขอลบข้อมูล

## 5. ต้องทำเอง (ไม่เร่ง)
- อัดเสียงตัวเองพูดแบบคุยงานจริงราว 5 นาที พร้อมพิมพ์บทถอดความทุกคำ วิธีดูใน `tools/asr-eval/README.md` — ส่วน FLEURS 25 นาทีโหลดไว้แล้ว
