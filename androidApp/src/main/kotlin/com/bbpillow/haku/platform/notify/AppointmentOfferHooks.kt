package com.bbpillow.haku.platform.notify

import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionRequestInput

/**
 * P5.8 seam between the OS entry points (notification listener, text-selection activity, offer receiver) and the
 * action gate. They only talk to these three lambdas, so none of them knows about `AppGraph`.
 *
 * wiring: Claude: set all three once in `HakuApplication.onCreate` after `graph` exists, e.g.
 * see there. Unset (null) = offers are dropped and buttons say "expired".
 */
object AppointmentOfferHooks {
  /**
   * Called on the main thread with a ready request and the chat app's display name (null = text the user selected);
   * must return quickly (launch a coroutine for `ActionGate.submit`).
   */
  @Volatile var onOffer: ((ActionRequestInput, String?) -> Unit)? = null

  /** `AppGraph.confirmOffer(id)`: null = the offer expired or the gate refused it. */
  @Volatile var confirm: (suspend (String) -> ActionProposalRecord?)? = null

  /** `AppGraph.dismissOffer(id)`. */
  @Volatile var dismiss: ((String) -> Unit)? = null
}
