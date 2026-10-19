package com.example.myplan.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialHour: Int = 8,
    initialMinute: Int = 30,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by rememberSaveable(initialHour, initialMinute) { mutableStateOf(initialHour.toString().padStart(2, '0')) }
    var minute by rememberSaveable(initialHour, initialMinute) { mutableStateOf(initialMinute.toString().padStart(2, '0')) }
    val parsedHour = hour.toIntOrNull()
    val parsedMinute = minute.toIntOrNull()
    val valid = parsedHour != null && parsedHour in 0..23 && parsedMinute != null && parsedMinute in 0..59
    val focusManager = LocalFocusManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("选择提醒时间", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text("使用 24 小时制", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimeInput(
                    value = hour,
                    onValueChange = { value -> hour = value.filter(Char::isDigit).take(2) },
                    label = "小时（00–23）",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next,
                )
                Text(":", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
                TimeInput(
                    value = minute,
                    onValueChange = { value -> minute = value.filter(Char::isDigit).take(2) },
                    label = "分钟（00–59）",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
            }
            if (!valid) Text("请输入有效的小时和分钟", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("取消") }
                Button(
                    onClick = { if (valid) { focusManager.clearFocus(); onConfirm(parsedHour!!, parsedMinute!!) } },
                    enabled = valid,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("确定时间") }
            }
        }
    }
}

@Composable
private fun TimeInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            keyboardActions = keyboardActions,
        )
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
