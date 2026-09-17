package com.pinotrouge.messaging.ui.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotChip
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotInput
import com.pinotrouge.messaging.ui.components.PinotMinTouchTarget
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import java.util.Locale

/**
 * Filter builder UI — ports PinotPhone.dc.html lines 349–431 (markup) and
 * 738–796 (behaviour). State is owned by the caller / ViewModel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuilderScreen(
    state: BuilderUiState,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onToggleMatch: () -> Unit,
    onFieldChange: (Int, ConditionField) -> Unit,
    onOperatorChange: (Int, String) -> Unit,
    onValueChange: (Int, String) -> Unit,
    onRemoveCondition: (Int) -> Unit,
    onAddCondition: () -> Unit,
    onToggleAction: (Action) -> Unit,
    onFewerDays: () -> Unit,
    onMoreDays: () -> Unit,
    onRequestDelete: () -> Unit = {},
    onDismissDeleteConfirm: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    val draft = state.draft
    val title = if (draft.isEditing) {
        stringResource(R.string.builder_title_edit)
    } else {
        stringResource(R.string.builder_title_new)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: Close · title · Save
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.dp, color = colors.divider)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PinotIconButton(
                    onClick = onClose,
                    contentDescription = stringResource(R.string.builder_close),
                    modifier = Modifier.testTag("builder_close"),
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.Close),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = PinotTypography.titleMedium.copy(fontSize = 15.sp),
                    color = colors.text,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("builder_title"),
                )
                PinotButton(
                    text = stringResource(R.string.builder_save),
                    onClick = onSave,
                    variant = PinotButtonVariant.Primary,
                    enabled = !state.saving && draft.canSave,
                    modifier = Modifier.testTag("builder_save"),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenPadding)
                    .padding(top = 14.dp, bottom = 24.dp),
            ) {
                PinotInput(
                    value = draft.name,
                    onValueChange = onNameChange,
                    label = stringResource(R.string.builder_name_label),
                    placeholder = stringResource(R.string.builder_name_placeholder),
                    enabled = !draft.isReadOnly,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .testTag("builder_name"),
                )

                if (draft.isReadOnly) {
                    Text(
                        text = UNSUPPORTED_RULE_REASON,
                        style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
                        color = colors.dim,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .testTag("builder_unsupported_reason"),
                    )
                } else if (draft.hasUnrunnableRegex) {
                    Text(
                        text = REGEX_UNRUNNABLE_REASON,
                        style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
                        color = colors.dim,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .testTag("builder_regex_unrunnable_reason"),
                    )
                }

                // When a message… · match all/any as a real control, not a caption.
                SectionHeader(kicker = stringResource(R.string.builder_when_kicker))
                Spacer(Modifier.height(8.dp))
                MatchModeSelector(
                    match = draft.match,
                    onToggleMatch = onToggleMatch,
                    enabled = !draft.isReadOnly,
                )
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.conditions.forEachIndexed { index, condition ->
                        ConditionCard(
                            index = index,
                            condition = condition,
                            match = draft.match,
                            canRemove = !draft.isReadOnly && draft.conditions.size > 1,
                            onFieldChange = { onFieldChange(index, it) },
                            onOperatorChange = { onOperatorChange(index, it) },
                            onValueChange = { onValueChange(index, it) },
                            onRemove = { onRemoveCondition(index) },
                            onToggleMatch = onToggleMatch,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                PinotButton(
                    text = stringResource(R.string.builder_add_condition),
                    onClick = onAddCondition,
                    variant = PinotButtonVariant.Secondary,
                    fillMaxWidth = true,
                    enabled = !draft.isReadOnly,
                    modifier = Modifier.testTag("builder_add_condition"),
                )

                Spacer(Modifier.height(20.dp))
                SectionHeader(kicker = stringResource(R.string.builder_then_kicker))
                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("builder_actions"),
                ) {
                    ACTION_CHIP_ORDER.forEach { action ->
                        PinotChip(
                            label = action.chipLabel(),
                            selected = action in draft.actions,
                            onClick = { onToggleAction(action) },
                            enabled = !draft.isReadOnly,
                            modifier = Modifier.testTag("builder_action_${action.name}"),
                        )
                    }
                }

                if (Action.DELETE in draft.actions) {
                    Spacer(Modifier.height(12.dp))
                    DeleteDaysStepper(
                        days = draft.deleteAfterDays,
                        onFewer = onFewerDays,
                        onMore = onMoreDays,
                    )
                }
                // Auto-reply field removed (fix/remove-auto-reply). Saved REPLY
                // actions still load and render in plain words; they do not send.

                Spacer(Modifier.height(20.dp))
                PlainWordsPanel(
                    plainWords = state.plainWords,
                    backtestLine = state.backtestLine,
                    modifier = Modifier.testTag("builder_plain_words"),
                )

                // Destructive row — edit only. No Version N design; brief copy.
                if (draft.isEditing) {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = stringResource(R.string.builder_delete_filter),
                        style = PinotTypography.labelLarge,
                        color = colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = PinotMinTouchTarget)
                            .clickable(
                                enabled = !state.deleting,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onRequestDelete,
                            )
                            .padding(vertical = 12.dp)
                            .testTag("builder_delete"),
                    )
                }
            }
        }

        if (state.showDeleteConfirm) {
            DeleteFilterConfirmDialog(
                filterName = draft.name.trim().ifEmpty { UNTITLED_FILTER_NAME },
                heldCount = state.deleteHeldCount,
                onConfirm = onConfirmDelete,
                onDismiss = onDismissDeleteConfirm,
            )
        }
    }
}

/**
 * Same AlertDialog shape as batch-delete / delete-all. Confirms irreversible
 * rule removal and states the real held-message count (or omits it when zero).
 * No undo — held messages are kept; the rule can be rewritten by hand.
 */
@Composable
private fun DeleteFilterConfirmDialog(
    filterName: String,
    heldCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val body = when {
        heldCount <= 0 -> null
        heldCount == 1 -> stringResource(R.string.builder_delete_body_one)
        else -> stringResource(R.string.builder_delete_body, heldCount)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.dim,
        title = {
            Text(
                text = stringResource(R.string.builder_delete_title, filterName),
                style = PinotTypography.titleMedium,
            )
        },
        text = body?.let {
            {
                Text(text = it, style = PinotTypography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("builder_delete_confirm"),
            ) {
                Text(
                    text = stringResource(R.string.builder_delete_confirm),
                    style = PinotTypography.labelLarge,
                    color = colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("builder_delete_cancel"),
            ) {
                Text(
                    text = stringResource(R.string.builder_delete_cancel),
                    style = PinotTypography.labelLarge,
                    color = colors.dim,
                )
            }
        },
    )
}

@Composable
private fun SectionHeader(
    kicker: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalPinotColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kicker.uppercase(Locale.getDefault()),
            style = PinotTypography.labelSmall,
            color = colors.accent,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.divider),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** All/Any chips. Same [onToggleMatch] as the per-condition AND/OR lead. */
@Composable
private fun MatchModeSelector(
    match: MatchMode,
    onToggleMatch: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag("builder_match_toggle"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PinotChip(
            label = stringResource(R.string.builder_match_all),
            selected = match == MatchMode.ALL,
            onClick = { if (match != MatchMode.ALL) onToggleMatch() },
            enabled = enabled,
            modifier = Modifier.testTag("builder_match_all"),
        )
        PinotChip(
            label = stringResource(R.string.builder_match_any),
            selected = match == MatchMode.ANY,
            onClick = { if (match != MatchMode.ANY) onToggleMatch() },
            enabled = enabled,
            modifier = Modifier.testTag("builder_match_any"),
        )
    }
}

@Composable
private fun ConditionCard(
    index: Int,
    condition: Condition,
    match: MatchMode,
    canRemove: Boolean,
    onFieldChange: (ConditionField) -> Unit,
    onOperatorChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    onToggleMatch: () -> Unit,
) {
    if (condition is Condition.Unsupported) {
        UnsupportedConditionCard(index = index, condition = condition)
        return
    }
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val spacing = LocalPinotSpacing.current
    val field = condition.field() ?: return
    val lead = when {
        index == 0 -> stringResource(R.string.builder_lead_when)
        match == MatchMode.ALL -> stringResource(R.string.builder_lead_and)
        else -> stringResource(R.string.builder_lead_or)
    }
    val matchLabel = if (match == MatchMode.ALL) {
        stringResource(R.string.builder_match_all)
    } else {
        stringResource(R.string.builder_match_any)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.surface)
            .border(1.dp, colors.shadowSmBorder, RoundedCornerShape(shapes.md))
            .padding(12.dp)
            .testTag("builder_condition_$index"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index == 0) {
                Text(
                    text = lead,
                    style = PinotTypography.bodyMedium.copy(fontSize = 12.sp),
                    color = colors.dim,
                    modifier = Modifier.testTag("builder_condition_lead_$index"),
                )
            } else {
                // Outline, never selected: this is a mode toggle, not a filter.
                // The filled treatment stays on the match all/any pair. Min
                // width is 48+18+18 so AND/OR reads as a pill, not a circle.
                PinotChip(
                    label = lead,
                    selected = false,
                    onClick = onToggleMatch,
                    modifier = Modifier
                        .widthIn(min = PinotMinTouchTarget + spacing.xxl + spacing.xxl)
                        // testTag must be OUTSIDE clearAndSetSemantics — a tag set
                        // after it is a descendant property and is cleared with the
                        // rest, which makes the node unfindable by tag.
                        .testTag("builder_condition_lead_$index")
                        .clearAndSetSemantics {
                            contentDescription = "$lead, $matchLabel"
                            text = AnnotatedString(lead)
                            role = Role.Button
                            onClick {
                                onToggleMatch()
                                true
                            }
                        },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.builder_remove),
                style = PinotTypography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (canRemove) colors.dimmer else colors.text.copy(alpha = colors.disabledOpacity),
                modifier = Modifier
                    .clickable(
                        enabled = canRemove,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemove,
                    )
                    .testTag("builder_remove_$index"),
            )
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stack = maxWidth < BuilderFieldOpStackBelow ||
                LocalDensity.current.fontScale >= BuilderFieldOpStackFontScale
            if (stack) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("builder_field_op_${index}_stacked"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuilderDropdown(
                        value = field.selectLabel,
                        options = ConditionField.entries.map { it.selectLabel to it },
                        onSelect = { onFieldChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_field_$index"),
                    )
                    BuilderDropdown(
                        value = condition.opLabel,
                        options = operatorsFor(field).map { (key, label) -> label to key },
                        onSelect = onOperatorChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_op_$index"),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("builder_field_op_${index}_row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuilderDropdown(
                        value = field.selectLabel,
                        options = ConditionField.entries.map { it.selectLabel to it },
                        onSelect = { onFieldChange(it) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("builder_field_$index"),
                    )
                    BuilderDropdown(
                        value = condition.opLabel,
                        options = operatorsFor(field).map { (key, label) -> label to key },
                        onSelect = onOperatorChange,
                        modifier = Modifier
                            .weight(1.65f)
                            .testTag("builder_op_$index"),
                    )
                }
            }
        }
        if (condition.needsValue) {
            Spacer(Modifier.height(8.dp))
            BuilderValueField(
                value = condition.value,
                onValueChange = onValueChange,
                placeholder = field.valuePlaceholder,
                modifier = Modifier.testTag("builder_value_$index"),
            )
        }
    }
}

@Composable
private fun UnsupportedConditionCard(
    index: Int,
    condition: Condition.Unsupported,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.surface)
            .border(1.dp, colors.shadowSmBorder, RoundedCornerShape(shapes.md))
            .padding(12.dp)
            .testTag("builder_condition_$index"),
    ) {
        Text(
            text = condition.fieldLabel,
            style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
            color = colors.dim,
            modifier = Modifier.testTag("builder_unsupported_row"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> BuilderDropdown(
    value: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = false,
            maxLines = 3,
            textStyle = PinotTypography.bodySmall.copy(fontSize = 12.5.sp, color = colors.text),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            shape = RoundedCornerShape(shapes.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.bg,
                unfocusedContainerColor = colors.bg,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.divider,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                cursorColor = colors.accent,
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface,
        ) {
            options.forEach { (label, item) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
                            color = colors.text,
                            maxLines = 3,
                        )
                    },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BuilderValueField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = PinotTypography.bodySmall.copy(fontSize = 12.5.sp, color = colors.text),
        placeholder = {
            Text(
                text = placeholder,
                style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
                color = colors.dimmer,
            )
        },
        shape = RoundedCornerShape(shapes.md),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.bg,
            unfocusedContainerColor = colors.bg,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.divider,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            cursorColor = colors.accent,
        ),
    )
}

@Composable
private fun DeleteDaysStepper(
    days: Int,
    onFewer: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.surface)
            .padding(12.dp)
            .testTag("builder_delete_days"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.builder_delete_after, days),
            style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        StepperButton(label = "–", onClick = onFewer, testTag = "builder_days_minus")
        Spacer(Modifier.width(8.dp))
        StepperButton(label = "+", onClick = onMore, testTag = "builder_days_plus")
    }
}

@Composable
private fun StepperButton(
    label: String,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalPinotColors.current
    // 48dp hit zone; 34dp bordered square is the visible artwork.
    Box(
        modifier = Modifier
            .size(PinotMinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.divider, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = PinotTypography.bodyLarge.copy(fontSize = 16.sp),
                color = colors.text,
            )
        }
    }
}

@Composable
private fun PlainWordsPanel(
    plainWords: String,
    backtestLine: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(colors.accent900)
            .border(1.dp, colors.accent800, RoundedCornerShape(shapes.md))
            .padding(13.dp),
    ) {
        Text(
            text = stringResource(R.string.builder_plain_kicker).uppercase(Locale.getDefault()),
            style = PinotTypography.labelSmall,
            color = colors.accent300,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = plainWords,
            style = PinotTypography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 13.sp * 1.55f,
            ),
            color = colors.accent100,
            modifier = Modifier.testTag("builder_plain_words_body"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = backtestLine,
            style = PinotTypography.bodySmall.copy(fontSize = 11.5.sp),
            color = colors.accent200,
            modifier = Modifier.testTag("builder_backtest"),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────

private fun previewState(
    draft: BuilderDraft,
    plainWords: String,
): BuilderUiState = BuilderUiState(
    draft = draft,
    plainWords = plainWords,
    backtestLine = formatBacktestLine(23, 200, 0),
    loaded = true,
)

@Preview(name = "Builder · New · Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun BuilderNewDarkPreview() {
    PinotRougeTheme(darkTheme = true) {
        BuilderScreen(
            state = previewState(
                draft = BuilderDraft.newRule(),
                plainWords = "When the sender is not in my contacts, hold it in Filtered.",
            ),
            onClose = {},
            onSave = {},
            onNameChange = {},
            onToggleMatch = {},
            onFieldChange = { _, _ -> },
            onOperatorChange = { _, _ -> },
            onValueChange = { _, _ -> },
            onRemoveCondition = {},
            onAddCondition = {},
            onToggleAction = {},
            onFewerDays = {},
            onMoreDays = {},
        )
    }
}

@Preview(name = "Builder · New · Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun BuilderNewLightPreview() {
    PinotRougeTheme(darkTheme = false) {
        BuilderScreen(
            state = previewState(
                draft = BuilderDraft.newRule(),
                plainWords = "When the sender is not in my contacts, hold it in Filtered.",
            ),
            onClose = {},
            onSave = {},
            onNameChange = {},
            onToggleMatch = {},
            onFieldChange = { _, _ -> },
            onOperatorChange = { _, _ -> },
            onValueChange = { _, _ -> },
            onRemoveCondition = {},
            onAddCondition = {},
            onToggleAction = {},
            onFewerDays = {},
            onMoreDays = {},
        )
    }
}

@Preview(name = "Builder · Multi · Dark", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun BuilderMultiDarkPreview() {
    val draft = BuilderDraft(
        existingId = "r1",
        name = "Links from people I do not know",
        match = MatchMode.ALL,
        conditions = listOf(
            Condition.Sender(SenderOp.NOT_IN_CONTACTS),
            Condition.Link(LinkOp.PRESENT),
        ),
        actions = linkedSetOf(Action.HOLD, Action.SILENCE),
    )
    PinotRougeTheme(darkTheme = true) {
        BuilderScreen(
            state = previewState(
                draft = draft,
                plainWords = "When the sender is not in my contacts and a link is present, hold it in Filtered, do not make a sound.",
            ),
            onClose = {},
            onSave = {},
            onNameChange = {},
            onToggleMatch = {},
            onFieldChange = { _, _ -> },
            onOperatorChange = { _, _ -> },
            onValueChange = { _, _ -> },
            onRemoveCondition = {},
            onAddCondition = {},
            onToggleAction = {},
            onFewerDays = {},
            onMoreDays = {},
        )
    }
}

@Preview(name = "Builder · Multi · Light", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun BuilderMultiLightPreview() {
    val draft = BuilderDraft(
        existingId = "r1",
        name = "Links from people I do not know",
        match = MatchMode.ALL,
        conditions = listOf(
            Condition.Sender(SenderOp.NOT_IN_CONTACTS),
            Condition.Link(LinkOp.PRESENT),
        ),
        actions = linkedSetOf(Action.HOLD, Action.SILENCE),
    )
    PinotRougeTheme(darkTheme = false) {
        BuilderScreen(
            state = previewState(
                draft = draft,
                plainWords = "When the sender is not in my contacts and a link is present, hold it in Filtered, do not make a sound.",
            ),
            onClose = {},
            onSave = {},
            onNameChange = {},
            onToggleMatch = {},
            onFieldChange = { _, _ -> },
            onOperatorChange = { _, _ -> },
            onValueChange = { _, _ -> },
            onRemoveCondition = {},
            onAddCondition = {},
            onToggleAction = {},
            onFewerDays = {},
            onMoreDays = {},
        )
    }
}

@Preview(name = "Builder · Delete days · Dark", showBackground = true, widthDp = 360, heightDp = 860)
@Composable
private fun BuilderDeleteDaysDarkPreview() {
    val draft = BuilderDraft(
        name = "Promotions and sales",
        match = MatchMode.ANY,
        conditions = listOf(
            Condition.Text(TextOp.CONTAINS_ANY, "sale, % off, coupon, deal"),
        ),
        actions = linkedSetOf(Action.HOLD, Action.SILENCE, Action.DELETE),
        deleteAfterDays = 14,
    )
    PinotRougeTheme(darkTheme = true) {
        BuilderScreen(
            state = previewState(
                draft = draft,
                plainWords = "When the message text contains any of \u201Csale, % off, coupon, deal\u201D, hold it in Filtered, do not make a sound, delete it after 14 days.",
            ),
            onClose = {},
            onSave = {},
            onNameChange = {},
            onToggleMatch = {},
            onFieldChange = { _, _ -> },
            onOperatorChange = { _, _ -> },
            onValueChange = { _, _ -> },
            onRemoveCondition = {},
            onAddCondition = {},
            onToggleAction = {},
            onFewerDays = {},
            onMoreDays = {},
        )
    }
}

@Preview(name = "Builder · Block · Light", showBackground = true, widthDp = 360, heightDp = 860)
@Composable
private fun BuilderBlockLightPreview() {
    val draft = BuilderDraft(
        name = "No offers",
        conditions = listOf(Condition.Sender(SenderOp.NOT_IN_CONTACTS)),
        actions = linkedSetOf(Action.HOLD, Action.BLOCK),
    )
    PinotRougeTheme(darkTheme = false) {
        BuilderScreen(
            state = previewState(
                draft = draft,
                plainWords = "When the sender is not in my contacts, hold it in Filtered, block the sender.",
            ),
            onClose = {},
            onSave = {},
            onNameChange = {},
            onToggleMatch = {},
            onFieldChange = { _, _ -> },
            onOperatorChange = { _, _ -> },
            onValueChange = { _, _ -> },
            onRemoveCondition = {},
            onAddCondition = {},
            onToggleAction = {},
            onFewerDays = {},
            onMoreDays = {},
        )
    }
}

/** Side-by-side field/operator below this width clips “is not in my contacts”. */
private val BuilderFieldOpStackBelow = 400.dp
private const val BuilderFieldOpStackFontScale = 1.3f
