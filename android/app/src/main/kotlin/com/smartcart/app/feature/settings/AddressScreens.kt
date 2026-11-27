package com.smartcart.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcart.app.R
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.data.address.AddressField
import com.smartcart.data.address.AddressProblem
import com.smartcart.data.address.AddressRules
import com.smartcart.data.address.SavedAddress
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.SecondaryButton
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import java.util.UUID

// ---------------------------------------------------------------- S-SET-08 (CR-004)
/** Saved delivery addresses: list, add, edit, delete, default, delete all. Everything stays on the phone. */
@Composable
fun AddressesScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val addresses by c.addresses.addresses.collectAsStateWithLifecycle()
    // null: the list; "": a new address; otherwise the id being edited.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    editing?.let { id ->
        AddressEditor(
            initial = addresses.firstOrNull { it.id == id },
            onDone = { editing = null },
        )
        return
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.addr_delete_all_title)) },
            text = { Text(stringResource(R.string.addr_delete_all_body)) },
            confirmButton = { TextButton(onClick = { c.addresses.deleteAll(); confirmDeleteAll = false }) { Text(stringResource(R.string.addr_delete_all_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    ScreenScaffold(stringResource(R.string.addr_title), onBack) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Banner(stringResource(R.string.addr_on_phone), tone = Tone.INFO) }
            if (addresses.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.addr_empty_title),
                        stringResource(R.string.addr_empty_body),
                        actionLabel = stringResource(R.string.addr_add),
                        onAction = { editing = "" },
                    )
                }
            } else {
                items(addresses, key = { it.id }) { a ->
                    val cd = stringResource(R.string.addr_edit_cd, a.label)
                    AppCard(Modifier.semantics { contentDescription = cd }, onClick = { editing = a.id }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (a.isDefault) Pill(stringResource(R.string.addr_default), tone = Tone.SUCCESS)
                        }
                        Text(a.oneLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Spacer(Modifier.size(Spacing.sm)); PrimaryButton(stringResource(R.string.addr_add), { editing = "" }) }
                item {
                    TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                        Text(stringResource(R.string.addr_delete_all), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** The add-address form as a full-screen dialog, for the cart card (CR-004 §3). */
@Composable
fun AddressEditorDialog(onSaved: (SavedAddress) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            AddressEditor(initial = null, onDone = { saved -> if (saved != null) onSaved(saved) else onDismiss() })
        }
    }
}

/** Add ([initial] null) or edit an address. [onDone] gets the saved address, or null for back / deleted. */
@Composable
fun AddressEditor(initial: SavedAddress?, onDone: (SavedAddress?) -> Unit) {
    val c = LocalAppContainer.current
    val saved by c.addresses.addresses.collectAsStateWithLifecycle()
    val first = remember { saved.isEmpty() } // the first address is always the default
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var flat by rememberSaveable { mutableStateOf(initial?.flatHouse.orEmpty()) }
    var street by rememberSaveable { mutableStateOf(initial?.streetArea.orEmpty()) }
    var landmark by rememberSaveable { mutableStateOf(initial?.landmark.orEmpty()) }
    var city by rememberSaveable { mutableStateOf(initial?.city.orEmpty()) }
    var pin by rememberSaveable { mutableStateOf(initial?.pinCode.orEmpty()) }
    var makeDefault by rememberSaveable { mutableStateOf(initial?.isDefault ?: first) }
    var problems by remember { mutableStateOf<Map<AddressField, AddressProblem>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.addr_delete_title, initial.label)) },
            text = { Text(stringResource(R.string.addr_delete_body)) },
            confirmButton = { TextButton(onClick = { c.addresses.delete(initial.id); confirmDelete = false; onDone(null) }) { Text(stringResource(R.string.addr_delete_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    fun save() {
        val candidate = SavedAddress(
            id = initial?.id ?: UUID.randomUUID().toString(),
            label = label, flatHouse = flat, streetArea = street, landmark = landmark.ifBlank { null },
            city = city, pinCode = pin, isDefault = makeDefault,
        )
        problems = c.addresses.save(candidate)
        if (problems.isEmpty()) onDone(c.addresses.addresses.value.firstOrNull { it.id == candidate.id })
    }

    ScreenScaffold(stringResource(if (initial == null) R.string.addr_new_title else R.string.addr_edit_title), onBack = { onDone(null) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AddressInput(label, { label = it.take(AddressRules.LABEL_MAX + 10) }, R.string.addr_label, problems[AddressField.LABEL], supporting = stringResource(R.string.addr_label_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                listOf(R.string.addr_label_home, R.string.addr_label_work).forEach { res ->
                    val text = stringResource(res)
                    AssistChip(onClick = { label = text }, label = { Text(text) }, modifier = Modifier.heightIn(min = Spacing.touchTarget))
                }
            }
            AddressInput(flat, { flat = it.take(100) }, R.string.addr_flat, problems[AddressField.FLAT_HOUSE])
            AddressInput(street, { street = it.take(100) }, R.string.addr_street, problems[AddressField.STREET_AREA])
            AddressInput(landmark, { landmark = it.take(100) }, R.string.addr_landmark, problems[AddressField.LANDMARK])
            AddressInput(city, { city = it.take(60) }, R.string.addr_city, problems[AddressField.CITY])
            AddressInput(pin, { v -> pin = v.filter(Char::isDigit).take(6) }, R.string.addr_pin, problems[AddressField.PIN_CODE], keyboard = KeyboardType.Number, imeAction = ImeAction.Done)
            SwitchRow(stringResource(R.string.addr_make_default), makeDefault, onChange = { makeDefault = it }, enabled = !(initial?.isDefault ?: first))
            Spacer(Modifier.size(Spacing.sm))
            PrimaryButton(stringResource(R.string.addr_save), ::save)
            if (initial != null) SecondaryButton(stringResource(R.string.addr_delete), { confirmDelete = true })
        }
    }
}

@Composable
private fun AddressInput(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    problem: AddressProblem?,
    supporting: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    val error = problem?.let { stringResource(it.messageRes()) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().then(if (error != null) Modifier.semantics { error(error) } else Modifier),
    )
}

private fun AddressProblem.messageRes() = when (this) {
    AddressProblem.REQUIRED -> R.string.addr_problem_required
    AddressProblem.TOO_LONG -> R.string.addr_problem_too_long
    AddressProblem.INVALID_PIN -> R.string.addr_problem_pin
    AddressProblem.DUPLICATE -> R.string.addr_problem_duplicate
}
