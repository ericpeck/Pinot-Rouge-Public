package com.pinotrouge.messaging.ui.onboarding

import androidx.annotation.StringRes
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp

/**
 * Starter filter packs for onboarding step 3.
 *
 * Rule definitions match the four seed rules from the Filter Rule Spec /
 * prototype (lines 511–514). **Not** the test fixture in `:core:rules`.
 */
enum class StarterPackKey {
    Links,
    Promos,
    Loans,
    Quiet,
}

data class StarterPack(
    val key: StarterPackKey,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descRes: Int,
    val defaultEnabled: Boolean,
    val rule: Rule,
)

object StarterFilterPacks {

    val all: List<StarterPack> = listOf(
        StarterPack(
            key = StarterPackKey.Links,
            nameRes = R.string.onboarding_pack_links_name,
            descRes = R.string.onboarding_pack_links_desc,
            defaultEnabled = true,
            rule = Rule(
                id = "r1",
                name = "Links from people I do not know",
                enabled = true,
                order = 0,
                match = MatchMode.ALL,
                conditions = listOf(
                    Condition.Sender(SenderOp.NOT_IN_CONTACTS),
                    Condition.Link(LinkOp.PRESENT),
                ),
                actions = linkedSetOf(Action.HOLD, Action.SILENCE),
                deleteAfterDays = 30,
            ),
        ),
        StarterPack(
            key = StarterPackKey.Promos,
            nameRes = R.string.onboarding_pack_promos_name,
            descRes = R.string.onboarding_pack_promos_desc,
            defaultEnabled = true,
            rule = Rule(
                id = "r2",
                name = "Promotions and sales",
                enabled = true,
                order = 1,
                match = MatchMode.ANY,
                conditions = listOf(
                    Condition.Text(TextOp.CONTAINS_ANY, "sale, % off, coupon, deal"),
                ),
                actions = linkedSetOf(Action.HOLD, Action.SILENCE, Action.DELETE),
                deleteAfterDays = 14,
            ),
        ),
        StarterPack(
            key = StarterPackKey.Loans,
            nameRes = R.string.onboarding_pack_loans_name,
            descRes = R.string.onboarding_pack_loans_desc,
            defaultEnabled = true,
            rule = Rule(
                id = "r3",
                name = "Loan and crypto offers",
                enabled = true,
                order = 2,
                match = MatchMode.ANY,
                conditions = listOf(
                    Condition.Text(
                        TextOp.MATCHES_REGEX,
                        "(pre-?approved|no credit check|crypto|wallet)",
                    ),
                ),
                actions = linkedSetOf(Action.HOLD, Action.BLOCK),
                deleteAfterDays = 30,
            ),
        ),
        StarterPack(
            key = StarterPackKey.Quiet,
            nameRes = R.string.onboarding_pack_quiet_name,
            descRes = R.string.onboarding_pack_quiet_desc,
            defaultEnabled = false,
            rule = Rule(
                id = "r4",
                name = "Quiet after 10 pm",
                enabled = false,
                order = 3,
                match = MatchMode.ALL,
                conditions = listOf(
                    Condition.Time(TimeOp.BETWEEN, "22:00 – 07:00"),
                ),
                actions = setOf(Action.SILENCE),
                deleteAfterDays = 30,
            ),
        ),
    )

    /** Build rules with [enabled] from the user's pack toggles. */
    fun rulesFor(enabled: Map<StarterPackKey, Boolean>): List<Rule> =
        all.map { pack ->
            val on = enabled[pack.key] ?: pack.defaultEnabled
            pack.rule.copy(enabled = on)
        }
}
