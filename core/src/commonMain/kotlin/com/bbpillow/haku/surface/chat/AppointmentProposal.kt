package com.bbpillow.haku.surface.chat

/**
 * "มีนัดขูดหินปูน 10.00น. พรุ่งนี้" typed in chat becomes a proposal card; nothing reaches the device calendar until the
 * user taps add. Labels are pre-formatted in core so the UI only renders.
 */
data class AppointmentProposal(
  val title: String,
  val startUtcMs: Long,
  val endUtcMs: Long,
  /** e.g. "ศ 31 ต.ค." */
  val dayLabel: String,
  /** e.g. "10:00–11:00" */
  val timeLabel: String,
  /** e.g. "ชนกับ “ประชุมทีมขาย” 09:30–10:30"; null = free. */
  val conflict: String? = null,
  val status: AppointmentStatus = AppointmentStatus.PENDING,
  val allDay: Boolean = false,
)

enum class AppointmentStatus { PENDING, SAVING, ADDED, DISMISSED, NEEDS_PERMISSION, FAILED }
