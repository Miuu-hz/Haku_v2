package com.bbpillow.haku.platform.notify

import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionRequestInput

/**
 * P5.8 seam between the OS entry points (notification listener, text-selection activity, offer receiver) and the
 * action gate. They only talk to these three lambdas, so none of them knows about `AppGraph`.
 *
 * wiring: Claude: set all three once in `HakuApplication.onCreate` after `graph` exists, e.g.
 * `onOffer = { r -> graph.scope.launch { (gate.submit(r) as? ActionDecision.AwaitingUser)?.let { AppointmentOfferNotifier.post(app, it.proposal) } } }`,
 * `confirm = gate::confirm`, `dismiss = gate::dismiss`. Unset (null) = offers are dropped and buttons say "expired".
 */
object AppointmentOfferHooks {
  /** Called on the main thread with a ready request; must return quickly (launch a coroutine for `ActionGate.submit`). */
  @Volatile var onOffer: ((ActionRequestInput) -> Unit)? = null

  /** `ActionGate.confirm(id)`: null = id unknown to the gate (process died since the offer was posted). */
  @Volatile var confirm: (suspend (String) -> ActionProposalRecord?)? = null

  /** `ActionGate.dismiss(id)`. */
  @Volatile var dismiss: ((String) -> Unit)? = null
}
