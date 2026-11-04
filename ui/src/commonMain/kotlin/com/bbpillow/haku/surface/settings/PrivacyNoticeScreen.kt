package com.bbpillow.haku.surface.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/** Who answers privacy questions. ponytail: rough draft for the closed beta; replace with the legal name and a real address. */
const val PRIVACY_CONTACT = "ทีมพัฒนา Haku (รุ่นทดสอบ) · ติดต่อผ่านช่องทางที่ได้รับตอนเข้าร่วมทดสอบ"
const val PRIVACY_NOTICE_VERSION = "2026-11-04"

/**
 * The full PDPA s.23 notice (what, why, where, how long, who else sees it, your rights, contact). The onboarding
 * Privacy step is the short version and links here. Keep it true to the build: a new data flow updates this text.
 */
internal val PrivacyNoticeSections: List<Pair<String, List<String>>> = listOf(
  "Haku เก็บอะไร และเอาไปทำอะไร" to listOf(
    "ตำแหน่งและการเคลื่อนไหว (ถ้าคุณอนุญาต): หาว่าวันนี้คุณไปไหนมาบ้าง เพื่อเขียนไดอารีและเตือนเรื่องที่ผูกกับสถานที่",
    "ปฏิทิน (ถ้าคุณอนุญาต): อ่านนัดของคุณเพื่อสรุปวัน และเพิ่มนัดเมื่อคุณกดยืนยันเท่านั้น",
    "แจ้งเตือนแชท (ถ้าคุณเปิด): อ่านเฉพาะแอปที่คุณเปิดสวิตช์ไว้ทีละแอป และใช้เฉพาะข้อความที่ดูเหมือนนัด กรองในหน่วยความจำ ไม่บันทึกข้อความแชทและชื่อผู้ส่ง ถ้าพบนัดจะเก็บเฉพาะชื่อนัด วันเวลา และชื่อแอป แบบเข้ารหัส เพื่อรอให้คุณกดเพิ่มหรือไม่ใช่",
    "งานที่คุณเพิ่มเอง: ชื่องาน กำหนดส่ง และความสำคัญ ใช้แสดงในหน้าแรกและหน้างาน",
    "สิ่งที่คุณจดลงบันทึกหรือไดอารี: เก็บเป็นความจำเพื่อให้ตอบเรื่องของคุณได้",
    "คำถามและคำตอบในแชท: เก็บเพื่อให้กลับมาคุยต่อได้ (ดูหัวข้อประวัติแชท)",
    "ชื่อเล่นและการตั้งค่า: ใช้เรียกคุณตามที่เลือก",
  ),
  "เก็บไว้ที่ไหน" to listOf(
    "ในเครื่องนี้เท่านั้น ข้อมูลถูกเข้ารหัส และกุญแจอยู่ในชิปความปลอดภัยของเครื่อง",
    "ไม่สำรองข้อมูลขึ้น cloud ผู้พัฒนาไม่ได้รับและเปิดอ่านข้อมูลเหล่านี้ไม่ได้",
    "AI ทำงานในเครื่อง ข้อความของคุณไม่ถูกส่งไปให้ AI ภายนอก ยกเว้นค่าที่คุณกดยืนยันส่งให้บริการภายนอกเอง (ดูหัวข้อบริการภายนอก)",
  ),
  "เก็บนานแค่ไหน" to listOf(
    "ไดอารีและความจำ: จนกว่าคุณจะลบ",
    "ประวัติแชท: 7 วัน (เรื่องที่ปักหมุดเก็บจนกว่าคุณจะลบ)",
    "บันทึกการเชื่อมต่อ: 30 วัน · ประวัติการทำงานของคำสั่ง: 90 วัน",
    "ผลที่ได้จากบริการภายนอก: 30 วัน (ดูหัวข้อบริการภายนอก)",
    "นัดที่พบจากแจ้งเตือนและรอคุณตัดสิน: จนกว่าคุณจะกดเพิ่มหรือไม่ใช่ หรือเลยเวลาจบของนัด",
    "งาน: จนกว่าคุณจะลบ งานที่ทำเสร็จแล้วลบเองใน 30 วัน",
  ),
  "การเชื่อมต่อออกนอกเครื่อง" to listOf(
    "ดาวน์โหลดโมเดล AI จาก Hugging Face (สหรัฐอเมริกา): ผู้ให้บริการเห็นหมายเลข IP และเวลา ไม่เห็นข้อมูลของคุณ",
    "ตั้งชื่อสถานที่อัตโนมัติ: ส่งพิกัดของสถานที่ที่คุณไปมา (ครั้งเดียวต่อสถานที่) ไปยังบริการค้นหาที่อยู่ของ Google ผ่านระบบ Android ซึ่งอาจอยู่นอกประเทศไทย เพื่อได้ชื่อถนนหรือย่าน ถ้าหาไม่ได้จะตั้งเป็น \"สถานที่ A\" \"สถานที่ B\" แทน ชื่อที่คุณตั้งเองใช้แทนชื่ออัตโนมัติเสมอ",
    "ซื้อ Haku Plus ผ่าน Google Play: Google ดูแลข้อมูลการชำระเงิน และระบบชำระเงินของ Google ส่งข้อมูลวินิจฉัยของตัวเองตามนโยบายของ Google",
    "เปิดใน Google Maps: เมื่อคุณแตะสถานที่บนการ์ดไทม์ไลน์เท่านั้น แอปส่งเฉพาะพิกัดของจุดนั้นให้ Google Maps ไม่ส่งชื่อสถานที่ แผนที่ในการ์ดวาดจากไฟล์ในเครื่อง ไม่ใช้เน็ต (ข้อมูลแผนที่ © OpenStreetMap contributors)",
    "ดูการเชื่อมต่อของแอปย้อนหลังได้ที่หน้า \"ความโปร่งใส\"",
  ),
  "บริการภายนอก (MCP)" to listOf(
    "คุณเป็นผู้เพิ่ม URL ของเซิร์ฟเวอร์ (https://) และเปิดใช้แต่ละเครื่องมือเองใน ตั้งค่า → ความสามารถเสริม (ตอนเพิ่มจะเชื่อมต่อไปดึงรายชื่อเครื่องมือ และเริ่มต้นปิดไว้ทุกตัว)",
    "เมื่อเรียกใช้เครื่องมือ จะไม่มีข้อมูลส่งออกไปจนกว่าคุณจะตรวจดูการ์ดคำสั่งแล้วกด \"ยืนยัน\" ด้วยตัวเอง",
    "สิ่งที่ส่งออกไปมีเฉพาะค่าที่ดึงจากข้อความคำสั่งของคุณตามช่องที่เครื่องมือกำหนด (ไม่เกิน 3 ช่อง ไม่ส่งไดอารีหรือความจำอื่น) ไปยัง URL ที่คุณตั้งไว้ ถ้าบริการต้องใช้กุญแจ API ที่คุณกรอกเอง กุญแจจะเก็บแบบเข้ารหัสในเครื่อง ส่งไปที่บริการนั้นเท่านั้น และลบไปพร้อมกับบริการ",
    "เซิร์ฟเวอร์ปลายทางอาจอยู่นอกประเทศไทยซึ่งมีมาตรฐานคุ้มครองข้อมูลต่างกัน คุณเป็นผู้เลือกปลายทางและยืนยันการส่งเอง ข้อมูลที่ส่งออกไปแล้วอยู่ภายใต้นโยบายของผู้ให้บริการนั้น โดย Haku ไม่ได้ควบคุมหรือรับผิดชอบต่อผู้ให้บริการปลายทาง",
    "รายการเซิร์ฟเวอร์และเครื่องมือเก็บแบบเข้ารหัสไว้ในเครื่องเท่านั้น (อยู่จนกว่าคุณจะกดลบบริการ) ส่วนบันทึกการเชื่อมต่อเก็บ 30 วัน และประวัติคำสั่งเก็บ 90 วันโดยไม่เก็บค่าที่ส่ง",
    "ถ้าเซิร์ฟเวอร์ระบุว่าเครื่องมือนั้นอ่านข้อมูลอย่างเดียว แอปจะเก็บค่าที่ส่งและผลที่ได้แบบเข้ารหัสในเครื่อง เพื่อตอบคำถามเดิมจนถึงตี 4 โดยไม่ส่งข้อมูลออกซ้ำ และบอกเวลาที่ดึงไว้ใต้คำตอบ เก็บ 30 วัน ลบทันทีเมื่อลบบริการนั้นหรือ \"ลืมทุกอย่าง\"",
    "หยุดใช้ได้ทุกเมื่อโดยปิดสวิตช์เครื่องมือหรือลบบริการใน ตั้งค่า → ความสามารถเสริม หรือกดหยุดคำสั่งทั้งหมดฉุกเฉินในหน้า ประวัติการทำงาน",
  ),
  "ประวัติแชท" to listOf(
    "คำถามและคำตอบในหน้า \"ถาม Haku\" เก็บแบบเข้ารหัสในเครื่องนี้ เพื่อให้กลับมาคุยต่อได้ ไม่ส่งออกนอกเครื่อง",
    "หายเองใน 7 วันนับจากข้อความล่าสุดของเรื่องนั้น เว้นแต่คุณปักหมุดไว้ ซึ่งจะเก็บจนกว่าคุณจะลบ",
    "ลบได้ทีละเรื่องที่ \"ดูแชทก่อนหน้า\" และ \"ลืมทุกอย่าง\" ลบแชททั้งหมดด้วย",
    "แชทไม่ถูกเขียนลงไดอารีหรือความจำ เว้นแต่คุณสั่งตั้งเตือนเงื่อนไข (เก็บในความตั้งใจค้างไว้) หรือกดยืนยันเพิ่มนัดจากแชท (บันทึกลงปฏิทินและจดเป็นบันทึกของวันนั้น)",
  ),
  "สิทธิ์ของคุณ" to listOf(
    "ดูว่า Haku จำอะไรไว้ (หน้าความจำ) และส่งออกเป็นไฟล์ได้",
    "ลบได้ทีละบรรทัด ทีละหมวด หรือ \"ลืมทุกอย่าง\" ลบแล้วกู้คืนไม่ได้",
    "ถอนสิทธิ์ตำแหน่ง ปฏิทิน หรือแจ้งเตือนได้ทุกเมื่อในตั้งค่า และปิดการอ่านแจ้งเตือนได้ทีละแอป",
    "ร้องเรียนต่อสำนักงานคณะกรรมการคุ้มครองข้อมูลส่วนบุคคล (สคส.) ได้",
  ),
  "ผู้ใช้อายุต่ำกว่า 20 ปี" to listOf(
    "Haku ไม่ได้ออกแบบมาสำหรับผู้เยาว์ ถ้าคุณอายุต่ำกว่า 20 ปี ให้ผู้ปกครองรับทราบก่อนใช้งาน",
  ),
  "ติดต่อ" to listOf(PRIVACY_CONTACT),
)

@Composable
fun PrivacyNoticeScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      Modifier.size(36.dp).clip(HakuRadius.Pill).clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }
    ScreenTitle("ประกาศความเป็นส่วนตัว", eyebrow = "ฉบับ $PRIVACY_NOTICE_VERSION")
    PrivacyNoticeSections.forEach { (title, lines) ->
      SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(title, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
          lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted) }
        }
      }
    }
  }
}
