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
```
