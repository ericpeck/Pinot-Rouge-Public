package com.pinotrouge.messaging.ui.search

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotIconButton
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotMinTouchTarget
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.LocalPinotSpacing
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTextStyles
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * Search tab entry. Wired from [com.pinotrouge.messaging.ui.nav.PinotNavHost]
 * via a fully-qualified call (no import in NavHost).
 *
 * @param onOpenThread conversation hit → thread screen
 * @param onOpenHeldMessage held hit → full-screen held message overlay
 */
@Composable
fun SearchRoute(
    onOpenThread: (threadId: Long) -> Unit,
    onOpenHeldMessage: (heldId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        ContactsRepository.clearCachedLookups(context)
        viewModel.refreshPermissions()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SearchScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onClearQuery = viewModel::clearQuery,
        onSuggestion = viewModel::applySuggestion,
        onOpenThread = onOpenThread,
        onOpenHeld = onOpenHeldMessage,
        onRequestPermission = {
            permissionLauncher.launch(viewModel.previewPermissionsToRequest())
        },
        modifier = modifier,
    )
}

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSuggestion: (String) -> Unit,
    onOpenThread: (threadId: Long) -> Unit,
    onOpenHeld: (heldId: String) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
            modifier = Modifier.padding(
                horizontal = spacing.screenPadding,
                vertical = 16.dp,
            ),
        )

        when (state.resultsBody) {
            SearchResultsBody.Idle -> {
                if (state.suggestions.isNotEmpty()) {
                    JumpToSection(
                        suggestions = state.suggestions,
                        onSuggestion = onSuggestion,
                    )
                }
            }
            SearchResultsBody.NeedsPermission -> {
                PermissionNeededSearch(
                    onRequestPermission = onRequestPermission,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
            }
            SearchResultsBody.Empty -> {
                SearchEmpty(
                    query = state.query,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
            }
            SearchResultsBody.Results -> {
                // Conversations first, then archive seam, then held — design order.
                val rows = buildList {
                    addAll(state.conversationHits)
                    addAll(state.archivedHits)
                    addAll(state.heldHits)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = spacing.scrollBottomInset),
                ) {
                    items(
                        items = rows,
                        key = { hit ->
                            when (hit.kind) {
                                SearchHitKind.Conversation,
                                SearchHitKind.Archived,
                                -> "c-${hit.threadId}"
                                SearchHitKind.Held -> "h-${hit.heldId}"
                            }
                        },
                    ) { hit ->
                        SearchResultRow(
                            hit = hit,
                            onClick = {
                                when (hit.kind) {
                                    SearchHitKind.Conversation,
                                    SearchHitKind.Archived,
                                    -> hit.threadId?.let(onOpenThread)
                                    SearchHitKind.Held ->
                                        hit.heldId?.let(onOpenHeld)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PinotMinTouchTarget)
            .clip(RoundedCornerShape(shapes.pill))
            .background(colors.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(PinotIcons.Search),
            contentDescription = null,
            tint = colors.dim,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = PinotTextStyles.bodySmall.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PinotMinTouchTarget),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = PinotMinTouchTarget),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_field_placeholder),
                                style = PinotTextStyles.bodySmall,
                                color = colors.dimmer,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (query.isNotEmpty()) {
            PinotIconButton(
                onClick = onClearQuery,
                contentDescription = stringResource(R.string.search_clear_a11y),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_x_circle),
                    contentDescription = null,
                    tint = colors.dim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun JumpToSection(
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenPadding),
    ) {
        SearchSectionHeader(stringResource(R.string.search_jump_to))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            suggestions.forEach { label ->
                Text(
                    text = label,
                    style = PinotTextStyles.meta,
                    color = colors.dim,
                    modifier = Modifier
                        .heightIn(min = PinotMinTouchTarget)
                        .clip(RoundedCornerShape(shapes.pill))
                        .border(1.dp, colors.divider, RoundedCornerShape(shapes.pill))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSuggestion(label) },
                        )
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(label: String) {
    val colors = LocalPinotColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 13.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.accent),
        )
        Text(
            text = label.uppercase(),
            style = PinotTextStyles.kicker,
            color = colors.dim,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.divider),
        )
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHitUi,
    onClick: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val spacing = LocalPinotSpacing.current
    val shapes = LocalPinotShapes.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .heightIn(min = spacing.rowMinHeightCompact)
            .padding(horizontal = spacing.screenPadding, vertical = spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.accent800)
                .border(1.dp, colors.accent300, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(hit.glyphRes),
                contentDescription = null,
                tint = colors.accent100,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = hit.name,
                    style = PinotTextStyles.body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = hit.timeLabel,
                    style = PinotTextStyles.timestamp,
                    color = colors.dimmer,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = hit.snippet,
                style = PinotTextStyles.meta,
                color = colors.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            hit.tag?.let { tag ->
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(tag.toStringRes()),
                    style = PinotTextStyles.kicker.copy(letterSpacing = TextUnit.Unspecified),
                    color = colors.dim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(shapes.pill))
                        .border(1.dp, colors.divider, RoundedCornerShape(shapes.pill))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchEmpty(
    query: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(PinotIcons.Search),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.search_empty_title, query),
            style = PinotTextStyles.name,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.search_empty_body),
            style = PinotTextStyles.meta,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PermissionNeededSearch(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.search_permission_title),
            style = PinotTypography.bodyLarge.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.search_permission_body),
            style = PinotTypography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PinotButton(
            text = stringResource(R.string.search_permission_cta),
            onClick = onRequestPermission,
            variant = PinotButtonVariant.Primary,
        )
    }
}

@DrawableRes
private fun SearchHitTag.toStringRes(): Int = when (this) {
    SearchHitTag.HeldByFilter -> R.string.search_tag_held
    SearchHitTag.Archived -> R.string.search_tag_archived
}

// —— Previews ——

private fun previewHits(): SearchUiState = SearchUiState(
    query = "flight",
    conversationHits = listOf(
        SearchHitUi(
            kind = SearchHitKind.Conversation,
            threadId = 1L,
            name = "Airline",
            timeLabel = "Yesterday",
            snippet = "Your flight AA142 is delayed by 40 minutes.",
            glyphRes = R.drawable.ic_user,
            tag = null,
        ),
    ),
    heldHits = listOf(
        SearchHitUi(
            kind = SearchHitKind.Held,
            heldId = "h1",
            name = "LOANFAST",
            timeLabel = "Mon",
            snippet = "PRE-APPROVED flight deal — no credit check",
            glyphRes = R.drawable.ic_funnel_simple,
            tag = SearchHitTag.HeldByFilter,
        ),
    ),
    canReadMessages = true,
)

@Preview(showBackground = true, name = "Search results light")
@Composable
private fun SearchResultsPreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        SearchScreen(
            state = previewHits(),
            onQueryChange = {},
            onClearQuery = {},
            onSuggestion = {},
            onOpenThread = {},
            onOpenHeld = {},
            onRequestPermission = {},
        )
    }
}

@Preview(showBackground = true, name = "Search idle light")
@Composable
private fun SearchIdlePreviewLight() {
    PinotRougeTheme(darkTheme = false) {
        SearchScreen(
            state = SearchUiState(
                suggestions = listOf("Mom", "Alice", "882041"),
                canReadMessages = true,
            ),
            onQueryChange = {},
            onClearQuery = {},
            onSuggestion = {},
            onOpenThread = {},
            onOpenHeld = {},
            onRequestPermission = {},
        )
    }
}

@Preview(showBackground = true, name = "Search empty dark")
@Composable
private fun SearchEmptyPreviewDark() {
    PinotRougeTheme(darkTheme = true) {
        SearchScreen(
            state = SearchUiState(query = "zzzz", canReadMessages = true),
            onQueryChange = {},
            onClearQuery = {},
            onSuggestion = {},
            onOpenThread = {},
            onOpenHeld = {},
            onRequestPermission = {},
        )
    }
}
