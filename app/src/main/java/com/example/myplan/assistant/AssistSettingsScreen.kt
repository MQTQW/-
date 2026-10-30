package com.example.myplan.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myplan.assistant.api.AssistConfiguration
import com.example.myplan.assistant.api.AssistException
import com.example.myplan.ui.component.CompanionButton
import com.example.myplan.ui.component.CompanionCard
import com.example.myplan.ui.component.CompanionHeader
import com.example.myplan.ui.component.CompanionNote

@Composable
fun AssistSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = remember(context) { AssistConfiguration(context) }
    val initial = remember(configuration) { configuration.load() }
    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    // Access codes deliberately stay out of saved state, logs and clipboard operations.
    var accessToken by remember { mutableStateOf(initial.accessToken) }
    var showToken by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val save = {
        showValidation = true
        notice = null
        when {
            endpoint.isBlank() -> error = "请填写 AI 服务地址。"
            accessToken.isBlank() -> error = "请填写服务部署者提供的访问码。"
            else -> try {
                configuration.save(endpoint, accessToken)
                focusManager.clearFocus()
                accessToken = ""
                onBack()
            } catch (e: AssistException) { error = e.userMessage }
        }
    }
    Scaffold(modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CompanionHeader("AI 服务", onBack = onBack) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                CompanionButton("保存配置", save, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CompanionCard {
                Text("让 AI 帮你整理，而由你决定", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text("复杂描述、计划拆解和行程建议需要连接你配置的 AI 服务。请向服务部署者获取服务地址和访问码。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("这里填写的是 MY PLAN 服务访问码，无需填写模型厂商的 API 密钥。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; error = null; notice = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("服务地址") },
                placeholder = { Text("https://你的域名/v1/assist") },
                supportingText = { Text("填写完整地址，以 /v1/assist 结尾。") },
                isError = showValidation && endpoint.isBlank(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            OutlinedTextField(value = accessToken, onValueChange = { accessToken = it; error = null; notice = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("访问码") },
                supportingText = { Text("访问码保存在本机，分享截图时请保持隐藏。") },
                isError = showValidation && accessToken.isBlank(), shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (showToken) "隐藏访问码" else "显示访问码")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            CompanionNote("只有在你主动使用智能整理、拆解或生成行程时，才会发送本次所需内容。提醒、行程、步骤进度、专注计时与积分保存在手机本地；不配置 AI 也可手动安排。")
            OutlinedButton(onClick = {
                configuration.clear()
                endpoint = ""
                accessToken = ""
                showToken = false
                showValidation = false
                error = null
                notice = "已清除 AI 服务配置，手动创建和简单快记仍可使用。"
                focusManager.clearFocus()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("清除 AI 服务配置")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
