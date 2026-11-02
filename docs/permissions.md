# สิทธิ์ของแอปสำหรับการจับบริบท (P2.2 · P2.5)

ประกาศไว้ใน `androidApp/src/main/AndroidManifest.xml` แล้ว แต่**ยังไม่มีหน้าขอสิทธิ์** และการจับข้อมูล**ไม่เริ่มเอง**
จนกว่าจะมีคนเรียก `CaptureController.start()` (ตอนนี้เรียกได้จาก debug build เท่านั้น ดูท้ายไฟล์) หน้าขอสิทธิ์ทำในเฟส onboarding

ข้อมูลทั้งหมดเก็บในเครื่อง (`noBackupFilesDir/haku.db`, `allowBackup=false`) ไม่ส่งออก จึงไม่เข้ามาตรา 28 (ส่งออกนอกประเทศ)
ยกเว้นผู้ใช้เลือกส่งให้ AI ภายนอกเอง (D22)

| สิทธิ์ | ใช้ทำอะไร | ถ้าไม่ให้ | PDPA |
|---|---|---|---|
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | ขอพิกัดหนึ่งครั้งตอนถูกปลุก (โหมดประหยัด Wi-Fi/เสาสัญญาณ ~100 ม.) และตั้งรั้วพิกัดรอบที่ที่กำลังอยู่ (รั้วต้องใช้ FINE) | ไม่มีไทม์ไลน์สถานที่ ไดอารีเหลือแค่นัดหมาย | ข้อมูลส่วนบุคคล ม.6 · ขอความยินยอม ม.19 · แจ้งวัตถุประสงค์และระยะเก็บ ม.23 · เก็บเท่าที่จำเป็น ม.22 (ไม่เปิด GPS ค้าง ไม่เก็บเส้นทาง เก็บเฉพาะจุดที่หยุดอยู่) |
| `ACCESS_BACKGROUND_LOCATION` | ให้รั้วพิกัดและการขอพิกัดทำงานตอนแอปไม่ได้เปิด ซึ่งคือทั้งวัน | ไทม์ไลน์ขาดเกือบทั้งหมด | เหมือนข้างบน และต้องแจ้งให้ชัดว่า "ตอนไม่ได้ใช้แอป" (ม.23) · ขอแยกขั้นหลังได้ foreground แล้ว (Android บังคับ) |
| `ACTIVITY_RECOGNITION` | ตัวปลุก: ระบบแจ้งเมื่อเปลี่ยนจากนิ่ง → เดิน → อยู่ในรถ แทนการเปิด GPS | ไม่มีตัวปลุก ไทม์ไลน์ไม่ทำงาน | ม.19 · ม.23 · ไม่ใช่ข้อมูลสุขภาพตาม ม.26 เพราะเก็บแค่ชนิดการเคลื่อนที่ ไม่นับก้าว/หัวใจ |
| `READ_CALENDAR` | อ่านนัดหมายเมื่อวาน–พรุ่งนี้ (อ่านอย่างเดียว) ใส่ในไดอารี | ไดอารีไม่มีนัดหมาย | ม.19 · ม.23 · **ระวัง ม.26**: ชื่อนัดอาจเผยข้อมูลอ่อนไหว (หมอ ศาสนา) ต้องแจ้งและให้ผู้ใช้ลบรายการได้ (มีแล้ว: ลบแล้วไม่กลับมา) |
| `RECEIVE_BOOT_COMPLETED` | ลงทะเบียนตัวปลุกใหม่หลังรีสตาร์ตเครื่อง/อัปเดตแอป (ทำเฉพาะถ้าเคยเปิดการจับไว้) | หลังรีสตาร์ตจะไม่จับจนกว่าเปิดแอป | ไม่เก็บข้อมูลเอง |

สัญญาณชาร์จและปลดล็อกไม่ต้องขอสิทธิ์ (ไม่เก็บว่าใช้แอปไหน) · สิทธิ์ของผู้ใช้ตาม ม.30–33 (ขอดู ลบ) ใช้กลไก `delete` ของ `CaptureEventRepository` ที่จำ id ไว้ไม่ให้เขียนกลับ

## การแจ้งเตือนสรุปเช้า/เย็น (P5.1 · P5.2)

| สิทธิ์ | ใช้ทำอะไร | ถ้าไม่ให้ | PDPA |
|---|---|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | แจ้งสรุปเช้า (เมื่อวาน + นัดวันนี้) และสรุปเย็น (วันนี้ + ชวนเล่าเพิ่ม) ช่อง "สรุปประจำวัน" | สวิตช์กลับเป็นปิด ไม่มีแจ้งเตือน ส่วนอื่นใช้ได้ตามเดิม | ขอเฉพาะตอนผู้ใช้เปิดสวิตช์ในหน้าตั้งค่า ทั้งสองตัวเริ่มปิด (ม.19 ไม่ติ๊กไว้ก่อน) · ข้อความสร้างในเครื่องจากไดอารีและสรุปเย็นที่มีอยู่ ไม่เรียกโมเดลใหม่ ไม่ส่งออก (ไม่เข้า ม.28) · หน้าจอล็อกเห็นแค่ "Haku มีสรุปให้คุณ" (`VISIBILITY_PRIVATE`) เพราะไดอารีอาจมีข้อมูลอ่อนไหว ม.26 |

ประกาศที่หน้าตั้งค่า: "แจ้งเตือนวันละไม่เกินหนึ่งครั้งต่อแบบ ไม่มีอะไรจะเล่าก็ไม่แจ้ง หน้าจอล็อกไม่แสดงเนื้อหา สร้างในเครื่องนี้ทั้งหมด"
· ไม่มีบรรทัดไดอารีของวันนั้น (และเช้าไม่มีนัด) = ไม่แจ้ง ไม่แต่งตัวเลข · ช้ากว่าเวลาที่ตั้งเกิน 2 ชม. (เครื่องปิดอยู่) = ข้ามวันนั้น
· ตั้งเวลาด้วย WorkManager งานครั้งเดียวต่อวันที่ตั้งงานถัดไปเอง รอดจากปิดแอป/รีสตาร์ต · log ใน debug มีแค่ชนิดและผล ไม่มีข้อความ

## การเข้าถึงการแจ้งเตือน: จับนัดจากแชท (P5.8)

| สิทธิ์ | ใช้ทำอะไร | ถ้าไม่ให้ | PDPA |
|---|---|---|---|
| การเข้าถึงการแจ้งเตือน (`BIND_NOTIFICATION_LISTENER_SERVICE`, ผู้ใช้เปิดเองใน ตั้งค่าระบบ → การเข้าถึงการแจ้งเตือน) | อ่านแจ้งเตือนแชทของ LINE · Messenger · WhatsApp · Telegram · Google Messages · Samsung Messages เพื่อหาประโยคนัด (เช่น \"นัดประชุม 10:00 พรุ่งนี้\") แล้วถามว่าจะเพิ่มลงปฏิทินไหม | ไม่มีข้อเสนอนัดจากแจ้งเตือน ยังเลือกข้อความแล้วกด \"Haku: เพิ่มนัด\" เองได้ ส่วนอื่นใช้ได้ตามเดิม | เริ่มปิด ไม่มีทางเปิดในแอป ผู้ใช้ต้องไปเปิดเองที่ระบบ (ม.19) · อ่านแค่ชื่อผู้ส่ง/กลุ่มและข้อความล่าสุดของแอปในรายการนี้ แอปอื่น (ธนาคาร ฯลฯ) ไม่เปิดอ่านเลย · เนื้อหาข้อความเป็นข้อมูลของบุคคลที่สาม จึงใช้ใน RAM ครั้งเดียวแล้วทิ้ง (ม.22) |

- **เก็บอะไร:** ไม่เก็บอะไรจากแจ้งเตือนเลย ไม่ลงฐานข้อมูล ไฟล์ความจำ ดัชนี หรือ log (release และ debug ไม่ log ข้อความ ชื่อผู้ส่ง หรือชื่อแพ็กเกจ) · ไม่ส่งออกนอกเครื่อง (ไม่เข้า ม.28) · ไม่ปลุกโมเดล AI แยกข้อความด้วยกฎใน RAM
- **อะไรถูกเขียน:** เฉพาะเมื่อผู้ใช้กด [เพิ่ม] บนการแจ้งเตือน \"เพิ่มนัดนี้ลงปฏิทินไหม\" เท่านั้น (ผ่านด่าน `ActionGate` → ปฏิทิน) · [ไม่ใช่] หรือไม่กด = ทิ้ง · ข้อเสนอค้างอยู่ใน RAM ถ้าแอปถูกปิดแล้วกดปุ่ม จะขึ้น \"ข้อเสนอนี้หมดอายุ\"
- **หน้าจอล็อก:** เห็นแค่ \"Haku พบข้อความที่น่าจะเป็นนัด\" (`VISIBILITY_PRIVATE`)
- **ปิดยังไง:** ตั้งค่าระบบ → การเข้าถึงการแจ้งเตือน → Haku → ปิด (หรือถอนสิทธิ์ที่นั่น) หยุดทันที ไม่มีอะไรต้องลบเพราะไม่ได้เก็บ
- **เลือกข้อความเอง:** เมนูเลือกข้อความของระบบ → \"Haku: เพิ่มนัด\" ไม่ต้องมีสิทธิ์พิเศษ ข้อความที่เลือกถูกใช้ครั้งเดียวในแอปและไม่เก็บเช่นกัน

## ความเสี่ยงรีวิว Google Play: ตำแหน่งเบื้องหลัง

Google Play ตรวจ `ACCESS_BACKGROUND_LOCATION` เข้มที่สุด ต้องกรอก Permissions Declaration Form + วิดีโอสั้นแสดงการเปิดเผยข้อมูลในแอป
และต้องมี **prominent disclosure** (หน้าจออธิบายในแอปก่อนหน้าต่างขอสิทธิ์ของระบบ ระบุว่าเก็บตำแหน่ง "แม้ปิดแอปหรือไม่ได้ใช้งาน" และเพื่ออะไร)
ถูกปฏิเสธบ่อยเมื่อ: ฟีเจอร์หลักไม่ได้ต้องใช้ตำแหน่งเบื้องหลังจริง · ไม่มีหน้าเปิดเผย · วิดีโอไม่เห็นขั้นตอน
แผนสำรองถ้าไม่ผ่าน: ลดเหลือ foreground-only + Activity Recognition (ไดอารีสถานที่จะขาดบางช่วง) หรือแจกนอก Play ในรุ่นทดสอบ

ข้อความเหตุผลที่เตรียมไว้ (ภาษาอังกฤษสำหรับฟอร์ม):

> Haku is an automatic personal diary. Its core feature writes, at the end of each day, where the user went
> ("arrived at the office at 9:10, left at 18:05") without the user doing anything. This requires knowing when the user
> arrives at and leaves a place while the app is closed. Haku does not track routes or keep GPS on: it uses Activity
> Recognition transitions and one geofence around the current stay as wake-ups, and takes a single balanced-power
> location fix per wake-up. Locations never leave the device, are not shared with third parties or used for ads, and the
> user can view and delete any recorded place in the app. Capture only starts after an in-app disclosure and explicit opt-in.

## การซื้อ Haku Plus (P9.4) · PDPA

Billing Library เพิ่มสิทธิ์ `com.android.vending.BILLING` ให้เอง (manifest merge) เป็นสิทธิ์ปกติ ไม่มีหน้าต่างขอสิทธิ์

| ข้อมูล | อยู่ที่ไหน | PDPA |
|---|---|---|
| การชำระเงิน บัตร บัญชี Google คำสั่งซื้อ | Google Play เท่านั้น (Google ดูแลข้อมูลการชำระเงินตามนโยบายของ Google) Haku ไม่เห็นและไม่เก็บ | Haku ไม่ได้เก็บ · หน้า Plus แจ้งว่าชำระผ่าน Google Play |
| สถานะสิทธิ์ `filesDir/entitlement.txt`: แผน · รหัสสินค้า · เวลาที่ตรวจล่าสุด · SHA-256 ของ purchase token · รหัสติดตั้งสุ่ม · กลุ่มราคา A/B | ในเครื่องเท่านั้น (`allowBackup=false`) ไม่มีเซิร์ฟเวอร์ของ Haku | ฐาน ม.24(3) ปฏิบัติตามสัญญา · เก็บเท่าที่จำเป็น ม.22: ไม่เก็บ token ดิบ logcat มีแค่รหัสผลลัพธ์ · รหัสติดตั้งสุ่มไม่ผูกกับตัวตน ใช้แค่เลือกกลุ่มราคา |
| ทดสอบราคา (กลุ่ม A/B) | กลุ่มคำนวณจากรหัสติดตั้งในเครื่อง ไม่ส่งออก · ผลวัด (อัตราซื้อต่อกลุ่ม) ดูจากรายงานของ Play Console ตาม base plan `price-a` / `price-b` | ไม่มี profiling ในแอป · ถ้าวันหนึ่งจะส่งผลทดลองออกจากเครื่อง ต้องขอความยินยอมแยก (ม.19) และนับใน egress |

- ฝั่ง Haku ไม่ส่งข้อมูลออก: คำขอทั้งหมดไปที่แอป Play Store ในเครื่อง ซึ่งคุยกับ Google เอง ตัวนับ egress จึงไม่เพิ่ม
- ไม่จ่ายหรือเลิกสมัคร = เหลือมุมมองแบบฟรี ความจำ ไดอารี และสถิติไม่ถูกล็อกหรือลบ (สัญญาที่หน้า Plus)
- "ลืมทุกอย่าง" ไม่ลบไฟล์สิทธิ์ เพราะเป็นหลักฐานการซื้อ ไม่ใช่ความจำ (ถ้าหายก็กู้คืนจาก Play ได้เมื่อออนไลน์)
- ไม่มีการตรวจฝั่งเซิร์ฟเวอร์ (ไม่มี backend): ข้อดีด้าน PDPA คือไม่มีข้อมูลการซื้อบนเซิร์ฟเวอร์ให้ต้องดูแล ข้อเสียคือเครื่องที่ root อาจปลอมสิทธิ์ได้ (ดู ponytail ใน `PlayBilling`)

## ทดสอบใน debug build (ก่อนมี onboarding)

```sh
PKG=com.bbpillow.haku
adb shell am start -n $PKG/.MainActivity   # เปิดครั้งแรกให้พ้นสถานะ stopped ไม่งั้น broadcast/ตัวปลุกไม่ถึง
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant $PKG android.permission.ACTIVITY_RECOGNITION
adb shell pm grant $PKG android.permission.READ_CALENDAR
adb shell am broadcast -n $PKG/.CaptureDebugReceiver -a com.bbpillow.haku.debug.START   # เริ่ม
adb shell am broadcast -n $PKG/.CaptureDebugReceiver -a com.bbpillow.haku.debug.DUMP --es day 2026-10-01
adb shell am broadcast -n $PKG/.CaptureDebugReceiver -a com.bbpillow.haku.debug.DIARY --es day 2026-10-01
adb shell am broadcast -n $PKG/.CaptureDebugReceiver -a com.bbpillow.haku.debug.STOP    # หยุด
adb logcat -s HakuCapture
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell am broadcast -n $PKG/.BriefDebugReceiver -a com.bbpillow.haku.debug.BRIEF --es kind morning   # หรือ evening: แจ้งเลย (ข้ามสวิตช์/เวลา/วันละครั้ง)
adb logcat -s HakuBrief
```
