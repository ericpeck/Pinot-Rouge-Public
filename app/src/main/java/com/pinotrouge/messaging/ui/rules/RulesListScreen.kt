package com.pinotrouge.messaging.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.LinkOp
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.SenderOp
import com.pinotrouge.messaging.rules.TextOp
import com.pinotrouge.messaging.rules.TimeOp
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotSwitch
import com.pinotrouge.messaging.ui.components.PinotTag
import com.pinotrouge.messaging.ui.components.PinotTagVariant
import com.pinotrouge.messaging.ui.components.PinotToast
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography
import kotlin.math.abs

/**
 * Filters tab entry point. ViewModel via hiltViewModel(); NavHost calls this
 * fully-qualified with no import (Wave 3 PinotNavHost protocol).
 */
@Composable
fun RulesListRoute(
    onNewFilter: () -> Unit,
    onEditFilter: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RulesListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RulesListScreen(
        state = state,
        onNewFilter = onNewFilter,
        onEditFilter = onEditFilter,
        onToggle = viewModel::setEnabled,
        onReorder = viewModel::reorder,
        onDismissToast = viewModel::dismissToast,
        onHostEntered = viewModel::onHostEntered,
        onHostLeft = viewModel::onHostLeft,
        modifier = modifier,
    )
}

@Composable
fun RulesListScreen(
    state: RulesListUiState,
    onNewFilter: () -> Unit,
    onEditFilter: (String) -> Unit,
    onToggle: (Rule, Boolean) -> Unit,
    onReorder: (List<Rule>) -> Unit = {},
    onDismissToast: () -> Unit = {},
    onHostEntered: () -> Unit = {},
    onHostLeft: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val listState = rememberLazyListState()

    // Local list for live drag; mirrors Flow when idle so DB remains source of truth.
    var displayItems by remember { mutableStateOf(state.items) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.items, draggingId) {
        if (draggingId == null) {
            displayItems = state.items
        }
    }

    val currentDisplay by rememberUpdatedState(displayItems)
    val currentDraggingId by rememberUpdatedState(draggingId)
    val enterHost by rememberUpdatedState(onHostEntered)
    val leaveHost by rememberUpdatedState(onHostLeft)

    // Claim the pending save toast while Filters is composed; clear on leave only
    // after claim so navigate-from-builder cannot race show() with dispose.
    // Trade-off vs toast-auto-dismiss only: leaving Filters mid-display drops the
    // toast (including rotation). That beats a stale confirmation on re-entry.
    DisposableEffect(Unit) {
        enterHost()
        onDispose { leaveHost() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Title + "New" live on OverlayHeader (PinotNavHost) — one header only.

            if (state.items.isEmpty() && draggingId == null) {
                RulesListEmpty(
                    onNewFilter = onNewFilter,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screenPadding)
                        .padding(bottom = spacing.scrollBottomInset),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = spacing.screenPadding,
                        end = spacing.screenPadding,
                        bottom = spacing.scrollBottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = displayItems,
                        key = { _, item -> item.rule.id },
                    ) { index, item ->
                        val isDragging = item.rule.id == draggingId
                        RuleCard(
                            item = item,
                            isDragging = isDragging,
                            dragOffsetY = if (isDragging) dragOffsetY else 0f,
                            onEdit = { onEditFilter(item.rule.id) },
                            onToggle = { enabled -> onToggle(item.rule, enabled) },
                            onDragStart = {
                                draggingId = item.rule.id
                                dragOffsetY = 0f
                            },
                            onDrag = { dragAmountY ->
                                dragOffsetY += dragAmountY
                                val from = currentDisplay.indexOfFirst {
                                    it.rule.id == currentDraggingId
                                }
                                if (from < 0) return@RuleCard
                                val target = targetIndexForDrag(
                                    listState = listState,
                                    fromIndex = from,
                                    dragOffsetY = dragOffsetY,
                                )
                                if (target != null && target != from) {
                                    displayItems = moveItem(currentDisplay, from, target)
                                    // Keep finger aligned with the card after swap.
                                    dragOffsetY = 0f
                                }
                            },
                            onDragEnd = {
                                val ordered = currentDisplay.map { it.rule }
                                draggingId = null
                                dragOffsetY = 0f
                                onReorder(ordered)
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffsetY = 0f
                                displayItems = state.items
                            },
                            onMoveUp = if (index > 0) {
                                {
                                    val moved = moveItem(displayItems, index, index - 1)
                                    displayItems = moved
                                    onReorder(moved.map { it.rule })
                                }
                            } else {
                                null
                            },
                            onMoveDown = if (index < displayItems.lastIndex) {
                                {
                                    val moved = moveItem(displayItems, index, index + 1)
                                    displayItems = moved
                                    onReorder(moved.map { it.rule })
                                }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .animateItem()
                                .zIndex(if (isDragging) 1f else 0f),
                        )
                    }
                    item(key = "footer") {
                        Text(
                            text = stringResource(R.string.rules_footer),
                            style = PinotTypography.bodySmall.copy(
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp,
                            ),
                            color = colors.dimmer,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        // Save confirmation from the builder — outlives navigation to this screen.
        PinotToast(
            message = state.toastMessage,
            onDismiss = onDismissToast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

/**
 * Pick the visible list index whose vertical centre is nearest the dragged
 * card's centre (list-item top + half height + finger offset).
 * Skips non-rule rows (footer).
 */
internal fun targetIndexForDrag(
    listState: LazyListState,
    fromIndex: Int,
    dragOffsetY: Float,
): Int? {
    val layoutInfo = listState.layoutInfo
    val draggedInfo = layoutInfo.visibleItemsInfo.find { it.index == fromIndex }
        ?: return null
    val draggedCenter = draggedInfo.offset + draggedInfo.size / 2f + dragOffsetY
    var best: LazyListItemInfo? = null
    var bestDistance = Float.MAX_VALUE
    for (info in layoutInfo.visibleItemsInfo) {
        if (info.key == "footer") continue
        val center = info.offset + info.size / 2f
        val distance = abs(center - draggedCenter)
        if (distance < bestDistance) {
            bestDistance = distance
            best = info
        }
    }
    return best?.index
}

@Composable
private fun RulesListEmpty(
    onNewFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // V5 `rulesEmpty`: funnel glyph 40dp in accent at 85%, 16dp above the title.
        // Opacity on the icon only — alpha on text is a rejection rule.
        Icon(
            painter = painterResource(PinotIcons.Filter),
            contentDescription = null,
            tint = colors.accent.copy(alpha = 0.85f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.rules_empty_title),
            style = PinotTypography.bodyLarge.copy(fontSize = 14.sp),
            color = colors.dim,
        )
        Text(
            text = stringResource(R.string.rules_empty_body),
            style = PinotTypography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            color = colors.dimmer,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
        )
        Spacer(Modifier.height(16.dp))
        PinotButton(
            text = stringResource(R.string.rules_empty_cta),
            onClick = onNewFilter,
            variant = PinotButtonVariant.Primary,
            leadingIcon = PinotIcons.Plus,
            modifier = Modifier.testTag("rules_empty_cta"),
        )
    }
}

@Composable
private fun RuleCard(
    item: RuleListItem,
    isDragging: Boolean,
    dragOffsetY: Float,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val shape = RoundedCornerShape(shapes.md)

    // shadow-md: neutral700 hairline + ambient 6dp (Pinot elev-md).
    val borderColor = if (isDragging) colors.neutral700 else colors.shadowSmBorder
    val elevation = if (isDragging) 6.dp else 0.dp

    fun Modifier.reorderDrag(): Modifier = pointerInput(item.rule.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            onDrag = { change, dragAmount ->
                change.consume()
                onDrag(dragAmount.y)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetY }
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, borderColor, shape)
            .semantics {
                val actions = buildList {
                    if (onMoveUp != null) {
                        add(
                            CustomAccessibilityAction("Move up") {
                                onMoveUp()
                                true
                            },
                        )
                    }
                    if (onMoveDown != null) {
                        add(
                            CustomAccessibilityAction("Move down") {
                                onMoveDown()
                                true
                            },
                        )
                    }
                }
                if (actions.isNotEmpty()) {
                    customActions = actions
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Drag handle — long-press starts reorder. Switch is outside this hit target.
            DragHandle(modifier = Modifier.reorderDrag())
            Column(
                // Long-press on the card body also reorders; short tap opens edit.
                // pointerInput claims long-press; clickable still handles short taps.
                modifier = Modifier
                    .weight(1f)
                    .reorderDrag()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEdit,
                    ),
            ) {
                Text(
                    text = item.rule.name,
                    style = PinotTypography.bodyLarge.copy(fontSize = 14.sp),
                    color = if (item.rule.enabled) colors.text else colors.dimmer,
                )
                Text(
                    text = item.summary,
                    style = PinotTypography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.dim,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            // Switch is outside the drag hit target — toggle never starts a drag.
            PinotSwitch(
                checked = item.rule.enabled,
                onCheckedChange = onToggle,
            )
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PinotTag(
                text = item.caughtLabel,
                variant = PinotTagVariant.Accent,
            )
            Text(
                text = item.lastCaughtLabel,
                style = PinotTypography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    letterSpacing = 0.sp,
                ),
                color = colors.dimmer,
            )
        }
    }
}

/** Quiet six-dot grip so the long-press affordance is discoverable. */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    val colors = LocalPinotColors.current
    val dot = colors.text.copy(alpha = 0.28f)
    Column(
        modifier = modifier
            .width(16.dp)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(dot),
                )
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(dot),
                )
            }
        }
    }
}

// —— Previews ——

private val previewEngine = DefaultRuleEngine()

private fun previewItem(
    rule: Rule,
    caught: Int,
    lastCaughtLabel: String,
): RuleListItem = RuleListItem(
    rule = rule,
    summary = previewEngine.summarize(rule),
    caughtLabel = formatCaughtThisMonth(caught),
    lastCaughtLabel = lastCaughtLabel,
)

private val previewRules: List<RuleListItem> = listOf(
    previewItem(
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
            actions = setOf(Action.HOLD, Action.SILENCE),
        ),
        caught = 34,
        lastCaughtLabel = "last caught 12 min ago",
    ),
    previewItem(
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
        caught = 118,
        lastCaughtLabel = "last caught 1 h ago",
    ),
    previewItem(
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
        ),
        caught = 61,
        lastCaughtLabel = "last caught 4 h ago",
    ),
    previewItem(
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
        ),
        caught = 0,
        lastCaughtLabel = "never run",
    ),
)

@Preview(name = "Filters · Dark · Populated", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RulesListPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        RulesListScreen(
            state = RulesListUiState(items = previewRules),
            onNewFilter = {},
            onEditFilter = {},
            onToggle = { _, _ -> },
        )
    }
}

@Preview(name = "Filters · Light · Populated", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RulesListPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        RulesListScreen(
            state = RulesListUiState(items = previewRules),
            onNewFilter = {},
            onEditFilter = {},
            onToggle = { _, _ -> },
        )
    }
}

@Preview(name = "Filters · Dark · Empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RulesListPreviewDarkEmpty() {
    PinotRougeTheme(darkTheme = true) {
        RulesListScreen(
            state = RulesListUiState(),
            onNewFilter = {},
            onEditFilter = {},
            onToggle = { _, _ -> },
        )
    }
}

@Preview(name = "Filters · Light · Empty", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RulesListPreviewLightEmpty() {
    PinotRougeTheme(darkTheme = false) {
        RulesListScreen(
            state = RulesListUiState(),
            onNewFilter = {},
            onEditFilter = {},
            onToggle = { _, _ -> },
        )
    }
}
