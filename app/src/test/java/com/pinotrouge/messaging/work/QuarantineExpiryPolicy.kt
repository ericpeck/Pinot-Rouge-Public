package com.pinotrouge.messaging.work

/**
 * Pure encoding of the quarantine table's expiry rows.
 * [com.pinotrouge.messaging.data.repo.QuarantineRepository.commitExpired] implements
 * this; these helpers exist so the policy is unit-tested without Room/Telephony.
 *
 * Important: when a provider write fails, the row is **retained** (skipped),
 * never deleted — same class of bug as silent message loss.
 */
object QuarantineExpiryPolicy {

    enum class Outcome {
        /** Rule has DELETE → row gone for good. */
        DeletedPermanently,

        /** No DELETE and provider write succeeded → filed as read, row removed. */
        FiledAsRead,

        /** No DELETE and write failed / role not held → row kept for next run. */
        RetainedForRetry,
    }

    /**
     * @param ruleHasDelete whether the catching rule includes [com.pinotrouge.messaging.rules.Action.DELETE]
     * @param providerWriteSucceeded null when no write was attempted (DELETE path)
     */
    fun outcome(ruleHasDelete: Boolean, providerWriteSucceeded: Boolean?): Outcome {
        if (ruleHasDelete) return Outcome.DeletedPermanently
        return when (providerWriteSucceeded) {
            true -> Outcome.FiledAsRead
            false, null -> Outcome.RetainedForRetry
        }
    }

    data class Summary(val deleted: Int, val filed: Int, val skipped: Int) {
        companion object {
            fun of(outcomes: List<Outcome>): Summary {
                var deleted = 0
                var filed = 0
                var skipped = 0
                for (o in outcomes) {
                    when (o) {
                        Outcome.DeletedPermanently -> deleted++
                        Outcome.FiledAsRead -> filed++
                        Outcome.RetainedForRetry -> skipped++
                    }
                }
                return Summary(deleted, filed, skipped)
            }
        }
    }
}
