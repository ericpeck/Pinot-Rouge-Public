package com.pinotrouge.messaging.ui.builder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Filter builder entry point. NavHost calls this fully-qualified with no import
 * (Wave 3 PinotNavHost protocol).
 *
 * @param ruleId existing rule id to edit, or null / "new" for create.
 * @param prefillSender when set (and no [ruleId]), seeds Sender IS + HOLD and
 *   name "Messages from &lt;sender&gt;".
 */
@Composable
fun BuilderRoute(
    ruleId: String?,
    prefillSender: String?,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BuilderViewModel = hiltViewModel(),
) {
    LaunchedEffect(ruleId, prefillSender) {
        viewModel.load(ruleId = ruleId, prefillSender = prefillSender)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BuilderScreen(
        state = state,
        onClose = onClose,
        onSave = { viewModel.save(onSaved = onSaved) },
        onNameChange = viewModel::setName,
        onToggleMatch = viewModel::toggleMatch,
        onFieldChange = viewModel::setField,
        onOperatorChange = viewModel::setOperator,
        onValueChange = viewModel::setValue,
        onRemoveCondition = viewModel::removeCondition,
        onAddCondition = viewModel::addCondition,
        onToggleAction = viewModel::toggleAction,
        onFewerDays = viewModel::fewerDeleteDays,
        onMoreDays = viewModel::moreDeleteDays,
        onRequestDelete = viewModel::requestDelete,
        onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
        onConfirmDelete = { viewModel.confirmDelete(onDeleted = onClose) },
        modifier = modifier,
    )
}
