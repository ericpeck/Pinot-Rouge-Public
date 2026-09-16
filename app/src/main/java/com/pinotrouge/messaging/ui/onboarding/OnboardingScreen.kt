package com.pinotrouge.messaging.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinotrouge.messaging.R
import com.pinotrouge.messaging.data.telephony.ContactsRepository
import com.pinotrouge.messaging.ui.components.PinotButton
import com.pinotrouge.messaging.ui.components.PinotButtonVariant
import com.pinotrouge.messaging.ui.components.PinotCard
import com.pinotrouge.messaging.ui.components.PinotIcons
import com.pinotrouge.messaging.ui.components.PinotSwitch
import com.pinotrouge.messaging.ui.theme.LocalPinotColors
import com.pinotrouge.messaging.ui.theme.LocalPinotShapes
import com.pinotrouge.messaging.ui.theme.PinotRougeTheme
import com.pinotrouge.messaging.ui.theme.PinotTypography

/**
 * First-run flow. Call [onFinished] when step 3 completes (or equivalent) so
 * the host can show the main shell. Does not own [MainActivity].
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Whether granted or denied, import still proceeds (Not now path).
    }
    // Preview-mode path: when the user skips the role, ask for READ_SMS +
    // READ_CONTACTS so the inbox can browse history. Never request these when
    // the role is held — they come with ROLE_SMS.
    val previewPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Permission grants do not notify ContactsContract. Retry the
        // observer that failed at process start, before READ_CONTACTS existed.
        ContactsRepository.clearCachedLookups(context)
        viewModel.skipRole()
    }

    LaunchedEffect(state.step) {
        if (state.step == 2) viewModel.onArrivedAtImportStep()
    }

    OnboardingContent(
        state = state,
        modifier = modifier,
        onGetStarted = viewModel::nextStep,
        onContinueRole = viewModel::openRoleSheet,
        onNotNow = {
            val perms = viewModel.previewPermissionsToRequest()
            if (perms.isNotEmpty()) {
                previewPermissionLauncher.launch(perms)
            } else {
                viewModel.skipRole()
            }
        },
        onImportContinue = viewModel::nextStep,
        onTogglePack = viewModel::togglePack,
        onFinish = { viewModel.finish(onFinished) },
        onPickPinot = { viewModel.pickRolePinot(true) },
        onPickMessages = { viewModel.pickRolePinot(false) },
        onCancelRole = viewModel::dismissRoleSheet,
        onConfirmRole = {
            viewModel.confirmRole {
                val intent = viewModel.roleRequestIntent()
                if (intent != null) {
                    roleLauncher.launch(intent)
                } else if (context is Activity) {
                    // Already held or unavailable — nothing to launch.
                }
            }
        },
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    onGetStarted: () -> Unit,
    onContinueRole: () -> Unit,
    onNotNow: () -> Unit,
    onImportContinue: () -> Unit,
    onTogglePack: (StarterPackKey) -> Unit,
    onFinish: () -> Unit,
    onPickPinot: () -> Unit,
    onPickMessages: () -> Unit,
    onCancelRole: () -> Unit,
    onConfirmRole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPinotColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            StepDots(current = state.step)
            Spacer(Modifier.height(28.dp))
            when (state.step) {
                0 -> StepWelcome(onGetStarted = onGetStarted)
                1 -> StepDefaultApp(
                    onContinue = onContinueRole,
                    onNotNow = onNotNow,
                )
                2 -> StepImport(
                    state = state,
                    onContinue = onImportContinue,
                )
                else -> StepPacks(
                    packs = state.packs,
                    onToggle = onTogglePack,
                    onFinish = onFinish,
                )
            }
        }

        if (state.showRoleSheet) {
            RoleSheet(
                pinotSelected = state.rolePickPinot,
                onPickPinot = onPickPinot,
                onPickMessages = onPickMessages,
                onCancel = onCancelRole,
                onConfirm = onConfirmRole,
            )
        }
    }
}

@Composable
private fun StepDots(current: Int) {
    val colors = LocalPinotColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(4) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i <= current) colors.accent else colors.neutral900,
                    ),
            )
        }
    }
}

@Composable
private fun StepWelcome(onGetStarted: () -> Unit) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val promises = listOf(
        R.string.onboarding_promise_1,
        R.string.onboarding_promise_2,
        R.string.onboarding_promise_3,
    )

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.accent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(PinotIcons.Chat),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_app_name),
            style = PinotTypography.headlineSmall.copy(
                fontSize = 30.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.02).em,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_tagline),
            style = PinotTypography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp),
            color = colors.dim,
        )
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            promises.forEach { res ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(PinotIcons.CheckCircle),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(19.dp),
                    )
                    Text(
                        text = stringResource(res),
                        style = PinotTypography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                        ),
                        color = colors.dim,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PinotButton(
            text = stringResource(R.string.onboarding_get_started),
            onClick = onGetStarted,
            fillMaxWidth = true,
            modifier = Modifier.height(46.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_footnote),
            style = PinotTypography.bodySmall,
            color = colors.dimmer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

@Composable
private fun StepDefaultApp(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val duties = listOf(
        R.string.onboarding_duty_1,
        R.string.onboarding_duty_2,
        R.string.onboarding_duty_3,
        R.string.onboarding_duty_4,
    )

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_role_title),
            style = PinotTypography.headlineSmall.copy(fontSize = 23.sp, lineHeight = 28.sp),
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_role_body),
            style = PinotTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
            color = colors.dim,
        )
        Spacer(Modifier.height(8.dp))
        PinotCard(kicker = stringResource(R.string.onboarding_duties_kicker), elevated = true) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                duties.forEach { res ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            text = "—",
                            color = colors.ink,
                            style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp),
                        )
                        Text(
                            text = stringResource(res),
                            style = PinotTypography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                lineHeight = 19.sp,
                            ),
                            color = colors.dim,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PinotButton(
            text = stringResource(R.string.onboarding_continue),
            onClick = onContinue,
            fillMaxWidth = true,
            modifier = Modifier.height(46.dp),
        )
        PinotButton(
            text = stringResource(R.string.onboarding_not_now),
            onClick = onNotNow,
            variant = PinotButtonVariant.Ghost,
            fillMaxWidth = true,
            modifier = Modifier.height(40.dp),
        )
    }
}

@Composable
private fun StepImport(
    state: OnboardingUiState,
    onContinue: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val label = if (state.importDone) {
        stringResource(R.string.onboarding_imported, state.importCount)
    } else {
        stringResource(R.string.onboarding_importing, state.importCount)
    }
    val cta = if (state.importBusy) {
        stringResource(R.string.onboarding_importing_cta)
    } else {
        stringResource(R.string.onboarding_continue)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_import_title),
            style = PinotTypography.headlineSmall.copy(fontSize = 23.sp, lineHeight = 28.sp),
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_import_body),
            style = PinotTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
            color = colors.dim,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = PinotTypography.bodySmall.copy(fontSize = 12.sp),
                color = colors.dim,
            )
            Text(
                text = stringResource(R.string.onboarding_percent, state.importPercent),
                style = PinotTypography.bodySmall.copy(fontSize = 12.sp),
                color = colors.ink,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.neutral900),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (state.importPercent / 100f).coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent),
            )
        }
        Spacer(Modifier.weight(1f))
        PinotButton(
            text = cta,
            onClick = onContinue,
            enabled = !state.importBusy,
            fillMaxWidth = true,
            modifier = Modifier.height(46.dp),
        )
    }
}

@Composable
private fun StepPacks(
    packs: Map<StarterPackKey, Boolean>,
    onToggle: (StarterPackKey) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_packs_title),
            style = PinotTypography.headlineSmall.copy(fontSize = 23.sp, lineHeight = 28.sp),
            color = colors.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_packs_body),
            style = PinotTypography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 21.sp),
            color = colors.dim,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StarterFilterPacks.all.forEach { pack ->
                val on = packs[pack.key] ?: pack.defaultEnabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(shapes.md))
                        .background(colors.surface)
                        .toggleable(
                            value = on,
                            onValueChange = { onToggle(pack.key) },
                            role = Role.Switch,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        )
                        .semantics(mergeDescendants = true) {}
                        .padding(12.dp)
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(pack.nameRes),
                            style = PinotTypography.bodyLarge.copy(fontSize = 14.sp),
                            color = colors.text,
                        )
                        Text(
                            text = stringResource(pack.descRes),
                            style = PinotTypography.bodySmall,
                            color = colors.dim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    PinotSwitch(
                        checked = on,
                        onCheckedChange = null,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PinotButton(
            text = stringResource(R.string.onboarding_open_inbox),
            onClick = onFinish,
            fillMaxWidth = true,
            modifier = Modifier.height(46.dp),
        )
    }
}

@Composable
fun RoleSheet(
    pinotSelected: Boolean,
    onPickPinot: () -> Unit,
    onPickMessages: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = shapes.lg, topEnd = shapes.lg))
                .background(colors.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(34.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.neutral700),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_role_sheet_title),
                style = PinotTypography.titleMedium.copy(fontSize = 17.sp),
                color = colors.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_role_sheet_body),
                style = PinotTypography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                color = colors.dim,
            )
            Spacer(Modifier.height(14.dp))
            RoleOption(
                name = stringResource(R.string.onboarding_role_option_pinot),
                selected = pinotSelected,
                onClick = onPickPinot,
            )
            RoleOption(
                name = stringResource(R.string.onboarding_role_option_messages),
                selected = !pinotSelected,
                onClick = onPickMessages,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PinotButton(
                    text = stringResource(R.string.onboarding_role_cancel),
                    onClick = onCancel,
                    variant = PinotButtonVariant.Secondary,
                )
                Spacer(Modifier.width(8.dp))
                PinotButton(
                    text = stringResource(R.string.onboarding_role_set_default),
                    onClick = onConfirm,
                    variant = PinotButtonVariant.Primary,
                )
            }
        }
    }
}

@Composable
private fun RoleOption(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPinotColors.current
    val shapes = LocalPinotShapes.current
    val bg = if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent
    val ring = if (selected) colors.accent else colors.neutral700
    val dot = if (selected) colors.accent else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.md))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
        Text(
            text = name,
            style = PinotTypography.bodyLarge.copy(fontSize = 14.sp),
            color = colors.text,
        )
    }
}

// —— Previews ——

@Preview(name = "Welcome · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewWelcomeDark() {
    PinotRougeTheme(darkTheme = true) {
        OnboardingContent(
            state = OnboardingUiState(step = 0),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}

@Preview(name = "Welcome · Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewWelcomeLight() {
    PinotRougeTheme(darkTheme = false) {
        OnboardingContent(
            state = OnboardingUiState(step = 0),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}

@Preview(name = "Default app · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewRoleDark() {
    PinotRougeTheme(darkTheme = true) {
        OnboardingContent(
            state = OnboardingUiState(step = 1),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}

@Preview(name = "Import · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewImportDark() {
    PinotRougeTheme(darkTheme = true) {
        OnboardingContent(
            state = OnboardingUiState(
                step = 2,
                importBusy = false,
                importDone = true,
                importCount = 1284,
                importPercent = 100,
            ),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}

@Preview(name = "Packs · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewPacksDark() {
    PinotRougeTheme(darkTheme = true) {
        OnboardingContent(
            state = OnboardingUiState(step = 3),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}

@Preview(name = "Role sheet · Dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewRoleSheetDark() {
    PinotRougeTheme(darkTheme = true) {
        OnboardingContent(
            state = OnboardingUiState(step = 1, showRoleSheet = true),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}

@Preview(name = "Role sheet · Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PreviewRoleSheetLight() {
    PinotRougeTheme(darkTheme = false) {
        OnboardingContent(
            state = OnboardingUiState(step = 1, showRoleSheet = true, rolePickPinot = false),
            onGetStarted = {},
            onContinueRole = {},
            onNotNow = {},
            onImportContinue = {},
            onTogglePack = {},
            onFinish = {},
            onPickPinot = {},
            onPickMessages = {},
            onCancelRole = {},
            onConfirmRole = {},
        )
    }
}
