package com.bbpillow.haku.thinking.summary

internal class FakeSummaries(vararg records: EveningSummaryRecord) : EveningSummaryRepository {
  val rows = records.associateBy { it.day }.toMutableMap()
  override fun find(day: String) = rows[day]
  override fun save(record: EveningSummaryRecord) { rows[record.day] = record }
  override fun delete(day: String) = rows.remove(day) != null
  override fun clear() = rows.clear()
}
