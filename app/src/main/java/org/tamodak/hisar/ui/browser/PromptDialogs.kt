package org.tamodak.hisar.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import org.tamodak.hisar.R
import org.tamodak.hisar.web.PagePrompt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/**
 * A dialog a page opened: alert, confirm, prompt, sign-in, a <select>'s options, a colour, a date
 * or time, "leave this page?", "send the form again?". Each is answered once; [onDone] then
 * removes it.
 */
@Composable
fun PromptDialog(prompt: PagePrompt, onDone: () -> Unit) {
    val dismiss = {
        prompt.dismiss()
        onDone()
    }
    val respond = { response: org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse ->
        prompt.respond(response)
        onDone()
    }
    when (val p = prompt.prompt) {
        is AlertPrompt -> SimpleDialog(p.title, p.message, confirm = stringResource(R.string.ok), onConfirm = dismiss, onDismiss = dismiss)
        is ButtonPrompt -> SimpleDialog(
            p.title, p.message,
            confirm = stringResource(R.string.ok), onConfirm = { respond(p.confirm(ButtonPrompt.Type.POSITIVE)) },
            dismiss = stringResource(R.string.cancel), onDismiss = { respond(p.confirm(ButtonPrompt.Type.NEGATIVE)) },
        )
        is TextPrompt -> TextDialog(p, onConfirm = { respond(p.confirm(it)) }, onDismiss = dismiss)
        is AuthPrompt -> AuthDialog(p, onConfirm = { user, password ->
            respond(if (user == null) p.confirm(password) else p.confirm(user, password))
        }, onDismiss = dismiss)
        is ChoicePrompt -> ChoiceDialog(p, respond, dismiss)
        is ColorPrompt -> ColorDialog(p, onConfirm = { respond(p.confirm(it)) }, onDismiss = dismiss)
        is DateTimePrompt -> DateTimeDialog(p, onConfirm = { respond(p.confirm(it)) }, onDismiss = dismiss)
        is BeforeUnloadPrompt -> SimpleDialog(
            stringResource(R.string.prompt_leave_title), stringResource(R.string.prompt_leave_message),
            confirm = stringResource(R.string.prompt_leave), onConfirm = { respond(p.confirm(AllowOrDeny.ALLOW)) },
            dismiss = stringResource(R.string.prompt_stay), onDismiss = { respond(p.confirm(AllowOrDeny.DENY)) },
        )
        is RepostConfirmPrompt -> SimpleDialog(
            stringResource(R.string.prompt_repost_title), stringResource(R.string.prompt_repost_message),
            confirm = stringResource(R.string.prompt_resend), onConfirm = { respond(p.confirm(AllowOrDeny.ALLOW)) },
            dismiss = stringResource(R.string.cancel), onDismiss = { respond(p.confirm(AllowOrDeny.DENY)) },
        )
        // Anything else (logins to save, credentials) Hisar doesn't offer.
        else -> LaunchedEffect(prompt) { dismiss() }
    }
}

@Composable
private fun SimpleDialog(
    title: String?,
    message: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = dismiss?.let { { TextButton(onClick = onDismiss) { Text(it) } } },
    )
}

@Composable
private fun TextDialog(prompt: TextPrompt, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(prompt.defaultValue.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = prompt.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            Column {
                prompt.message?.let { Text(it, Modifier.padding(bottom = 12.dp)) }
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** HTTP sign-in. [onConfirm] gets a null user name when the site asks for a password only. */
@Composable
private fun AuthDialog(prompt: AuthPrompt, onConfirm: (String?, String) -> Unit, onDismiss: () -> Unit) {
    val passwordOnly = prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
    var user by rememberSaveable { mutableStateOf(prompt.authOptions.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.prompt_sign_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.message?.let { Text(it) }
                if (!passwordOnly) {
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.prompt_username)) }, singleLine = true)
                }
                OutlinedTextField(
                    password, { password = it },
                    label = { Text(stringResource(R.string.prompt_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(if (passwordOnly) null else user, password) }) { Text(stringResource(R.string.prompt_sign_in)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A row of a <select>: a group's label, a separator, or an option. */
private sealed interface ChoiceRow {
    data class Group(val label: String) : ChoiceRow
    data object Separator : ChoiceRow
    data class Option(val choice: ChoicePrompt.Choice) : ChoiceRow
}

private fun rows(choices: Array<out ChoicePrompt.Choice>): List<ChoiceRow> = buildList {
    for (choice in choices) {
        when {
            choice.separator -> add(ChoiceRow.Separator)
            choice.items != null -> {
                add(ChoiceRow.Group(choice.label))
                choice.items!!.forEach { add(ChoiceRow.Option(it)) }
            }
            else -> add(ChoiceRow.Option(choice))
        }
    }
}

/** A <select>: one option is picked with a tap; a multiple select has check boxes and OK. */
@Composable
private fun ChoiceDialog(
    prompt: ChoicePrompt,
    respond: (org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse) -> Unit,
    onDismiss: () -> Unit,
) {
    val multiple = prompt.type == ChoicePrompt.Type.MULTIPLE
    val rows = remember(prompt) { rows(prompt.choices) }
    val selected = remember(prompt) {
        mutableStateListOf<ChoicePrompt.Choice>().apply { addAll(rows.filterIsInstance<ChoiceRow.Option>().map { it.choice }.filter { it.selected }) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = prompt.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows) { row ->
                    when (row) {
                        is ChoiceRow.Group -> Text(
                            row.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        ChoiceRow.Separator -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        is ChoiceRow.Option -> {
                            val choice = row.choice
                            val checked = choice in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !choice.disabled) {
                                        if (!multiple) respond(prompt.confirm(choice))
                                        else if (checked) selected.remove(choice) else selected.add(choice)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (multiple) Checkbox(checked, null, enabled = !choice.disabled) else RadioButton(checked, null, enabled = !choice.disabled)
                                Text(choice.label, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) TextButton(onClick = { respond(prompt.confirm(selected.toTypedArray())) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** <input type=color>: the page's suggested colours, then a basic palette. */
@Composable
private fun ColorDialog(prompt: ColorPrompt, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = remember(prompt) {
        (prompt.predefinedValues.orEmpty().toList() + PALETTE).map { it.lowercase() }.distinct()
    }
    val current = prompt.defaultValue?.lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = prompt.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                colors.forEach { hex ->
                    val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() ?: return@forEach
                    Spacer(
                        Modifier
                            .size(40.dp)
                            .background(color, CircleShape)
                            .border(if (hex == current) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onConfirm(hex) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val PALETTE = listOf(
    "#000000", "#5f6368", "#ffffff", "#d93025", "#f29900", "#fbbc04",
    "#188038", "#12b5cb", "#1a73e8", "#4b0082", "#a142f4", "#e52592",
)

/**
 * <input type=date|month|week|time|datetime-local>, in the formats HTML uses: 2026-10-02,
 * 2026-10, 2026-W40, 14:30, 2026-10-02T14:30. A date and time is picked in two steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(prompt: DateTimePrompt, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val default = prompt.defaultValue.orEmpty()
    val initialDate = remember(prompt) { parseDate(prompt.type, default) }
    val initialTime = remember(prompt) { parseTime(prompt.type, default) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val needsDate = prompt.type != DateTimePrompt.Type.TIME
    val needsTime = prompt.type == DateTimePrompt.Type.TIME || prompt.type == DateTimePrompt.Type.DATETIME_LOCAL

    if (needsDate && pickedDate == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initialDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: return@TextButton
                    if (needsTime) pickedDate = date else onConfirm(formatDate(prompt.type, date))
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onConfirm("") }) { Text(stringResource(R.string.clear)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            },
        ) { DatePicker(state) }
        return
    }
    val state = rememberTimePickerState(initialTime.hour, initialTime.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                val time = "%02d:%02d".format(state.hour, state.minute)
                onConfirm(pickedDate?.let { "${it.format(DateTimeFormatter.ISO_LOCAL_DATE)}T$time" } ?: time)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun parseDate(type: Int, value: String): LocalDate? = runCatching {
    when (type) {
        DateTimePrompt.Type.MONTH -> LocalDate.parse("$value-01")
        DateTimePrompt.Type.WEEK -> {
            val (year, week) = value.split("-W").map(String::toInt)
            LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
        }
        else -> LocalDate.parse(value.substringBefore('T'))
    }
}.getOrNull()

private fun parseTime(type: Int, value: String): LocalTime = runCatching {
    LocalTime.parse(if (type == DateTimePrompt.Type.DATETIME_LOCAL) value.substringAfter('T') else value)
}.getOrDefault(LocalTime.now())

private fun formatDate(type: Int, date: LocalDate): String = when (type) {
    DateTimePrompt.Type.MONTH -> "%04d-%02d".format(date.year, date.monthValue)
    DateTimePrompt.Type.WEEK -> "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    else -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}
