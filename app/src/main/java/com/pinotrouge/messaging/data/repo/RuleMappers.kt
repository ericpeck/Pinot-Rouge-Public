package com.pinotrouge.messaging.data.repo

import com.pinotrouge.messaging.data.room.RuleEntity
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Rule

internal fun RuleEntity.toDomain(): Rule = Rule(
    id = id,
    name = name,
    enabled = enabled,
    order = order,
    match = match,
    conditions = conditions,
    // LinkedHashSet preserves the ordered list from storage.
    actions = actions.toCollection(LinkedHashSet()),
    deleteAfterDays = deleteAfterDays,
    autoReplyText = autoReplyText,
)

internal fun Rule.toEntity(): RuleEntity = RuleEntity(
    id = id,
    name = name,
    enabled = enabled,
    order = order,
    match = match,
    conditions = conditions,
    // Materialise Set as List in encounter order for stable JSON.
    actions = actions.toList(),
    deleteAfterDays = deleteAfterDays,
    autoReplyText = autoReplyText,
)

/** Stable ordered action list for tests / rendering helpers. */
fun orderedActions(actions: Set<Action>): List<Action> = actions.toList()
