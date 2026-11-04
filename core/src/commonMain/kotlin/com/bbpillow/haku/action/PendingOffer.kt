package com.bbpillow.haku.action

/*
 * P5.8 shared contract: an appointment found in a notification, waiting for the user's tap, kept so it survives the
 * process (the gate itself holds proposals in RAM only). Owned by Claude: agents implement against it but do not change it.
 */

/**
 * Only what the card shows and what `calendar.add` needs. Never the source message or the sender.
 *
 * @property id the gate proposal's id when it was offered; the heads-up buttons carry it.
 * @property appName display name of the chat app, e.g. "LINE"; null = text the user selected themselves.
 * @property expiresUtcMs when the appointment ends: nothing left to decide after that.
 */
data class PendingOfferRecord(
  val id: String,
  val dedupeKey: String,
  val title: String,
  val detail: String,
  val startUtcMs: Long,
  val endUtcMs: Long,
  val allDay: Boolean,
  val origin: ActionOrigin,
  val appName: String?,
  val warning: String?,
  val createdUtcMs: Long,
  val expiresUtcMs: Long,
)

/** SQL lives in `PendingOfferStore` (same DB as capture; not thread-safe, callers serialize). [clear] is called by ForgetEverything. */
interface PendingOfferRepository {
  /** Not yet expired at [nowUtcMs], soonest appointment first. */
  fun active(nowUtcMs: Long): List<PendingOfferRecord>
  fun find(id: String): PendingOfferRecord?

  /** One row per [PendingOfferRecord.dedupeKey]: the same appointment offered again replaces the older row. */
  fun put(record: PendingOfferRecord)
  fun delete(id: String)

  /** Removes rows expired at [nowUtcMs]; returns how many went. */
  fun deleteExpired(nowUtcMs: Long): Int
  fun clear()
}

object PendingOffers {
  /** Null when [proposal] is not a well-formed `calendar.add`. */
  fun of(proposal: ActionProposalRecord, appName: String?, nowUtcMs: Long): PendingOfferRecord? {
    val request = proposal.request
    if (request.action != CalendarAddArgs.ACTION) return null
    val args = CalendarAddArgs.parse(request.arguments) ?: return null
    return PendingOfferRecord(
      id = proposal.id,
      dedupeKey = request.dedupeKey,
      title = args.title,
      detail = request.detail,
      startUtcMs = args.startUtcMs,
      endUtcMs = args.endUtcMs,
      allDay = args.allDay,
      origin = request.origin,
      appName = appName,
      warning = request.warning,
      createdUtcMs = nowUtcMs,
      expiresUtcMs = args.endUtcMs,
    )
  }

  /** The request again, for a tap that comes after the process died and the gate forgot the proposal. */
  fun toRequest(offer: PendingOfferRecord): ActionRequestInput = ActionRequestInput(
    action = CalendarAddArgs.ACTION,
    arguments = buildMap {
      put(CalendarAddArgs.KEY_TITLE, offer.title)
      put(CalendarAddArgs.KEY_START_UTC_MS, offer.startUtcMs.toString())
      put(CalendarAddArgs.KEY_END_UTC_MS, offer.endUtcMs.toString())
      if (offer.allDay) put(CalendarAddArgs.KEY_ALL_DAY, "true")
    },
    title = offer.title,
    detail = offer.detail,
    origin = offer.origin,
    warning = offer.warning,
    dedupeKey = offer.dedupeKey,
  )
}
