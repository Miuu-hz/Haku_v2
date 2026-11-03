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

/** Who answers privacy questions. ponytail: placeholder until the company name and contact address are decided. */
const val PRIVACY_CONTACT = "ผู้พัฒนา Haku · ช่องทางติดต่อ: รอประกาศก่อนเปิดทดสอบ"
const val PRIVACY_NOTICE_VERSION = "2026-11-03"

/**
 * The full PDPA s.23 notice (what, why, where, how long, who else sees it, your rights, contact). The onboarding
 * Privacy step is the short version and links here. Keep it true to the build: a new data flow updates this text.
 */
internal val PrivacyNoticeSections: List<Pair<String, List<String>>> = listOf(
  "Haku เก็บอะไร และเอาไปทำอะไร" to listOf(
    "ตำแหน่งและการเคลื่อนไหว (ถ้าคุณอนุญาต): หาว่าวันนี้คุณไปไหนมาบ้าง เพื่อเขียนไดอารีและเตือนเรื่องที่ผูกกับสถานที่",
    "ปฏิทิน (ถ้าคุณอนุญาต): อ่านนัดของคุณเพื่อสรุปวัน และเพิ่มนัดเมื่อคุณกดยืนยันเท่านั้น",
    "แจ้งเตือนแชท (ถ้าคุณเปิด): อ่านเฉพาะข้อความที่ดูเหมือนนัด กรองในหน่วยความจำ ไม่บันทึกข้อความแชท เก็บแค่นัดที่คุณกดยืนยัน",
    "สิ่งที่คุณพิมพ์หรือคุยกับ Haku: เก็บเป็นความจำเพื่อให้ตอบเรื่องของคุณได้",
    "ชื่อเล่นและการตั้งค่า: ใช้เรียกคุณตามที่เลือก",
  ),
  "เก็บไว้ที่ไหน" to listOf(
    "ในเครื่องนี้เท่านั้น ข้อมูลถูกเข้ารหัส และกุญแจอยู่ในชิปความปลอดภัยของเครื่อง",
    "ไม่สำรองข้อมูลขึ้น cloud ผู้พัฒนาไม่ได้รับและเปิดอ่านข้อมูลเหล่านี้ไม่ได้",
    "AI ทำงานในเครื่อง ข้อความของคุณไม่ถูกส่งไปให้ AI ภายนอก",
  ),
  "เก็บนานแค่ไหน" to listOf(
    "ไดอารีและความจำ: จนกว่าคุณจะลบ",
    "บันทึกการเชื่อมต่อ: 30 วัน · ประวัติการทำงานของคำสั่ง: 90 วัน",
  ),
  "การเชื่อมต่อออกนอกเครื่อง" to listOf(
    "ดาวน์โหลดโมเดล AI จาก Hugging Face (สหรัฐอเมริกา): ผู้ให้บริการเห็นหมายเลข IP และเวลา ไม่เห็นข้อมูลของคุณ",
    "ซื้อ Haku Plus ผ่าน Google Play: Google ดูแลข้อมูลการชำระเงิน และระบบชำระเงินของ Google ส่งข้อมูลวินิจฉัยของตัวเองตามนโยบายของ Google",
    "ดูการเชื่อมต่อของแอปย้อนหลังได้ที่หน้า \"ความโปร่งใส\"",
  ),
  "สิทธิ์ของคุณ" to listOf(
    "ดูว่า Haku จำอะไรไว้ (หน้าความจำ) และส่งออกเป็นไฟล์ได้",
    "ลบได้ทีละบรรทัด ทีละหมวด หรือ \"ลืมทุกอย่าง\" ลบแล้วกู้คืนไม่ได้",
    "ถอนสิทธิ์ตำแหน่ง ปฏิทิน หรือแจ้งเตือนได้ทุกเมื่อในตั้งค่า",
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
