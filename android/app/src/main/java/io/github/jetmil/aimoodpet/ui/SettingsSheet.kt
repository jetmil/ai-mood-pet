package io.github.jetmil.aimoodpet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jetmil.aimoodpet.settings.DialogMode
import io.github.jetmil.aimoodpet.settings.TamaSettings
import io.github.jetmil.aimoodpet.settings.UserConfig
import io.github.jetmil.aimoodpet.settings.VoiceStyle

private val Accent = Color(0xFF21A038)
private val LineColor = Color(0xFFF4ECDC)
private val MutedColor = Color(0xFF8C7B5F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    current: TamaSettings,
    userConfig: UserConfig,
    onChange: (TamaSettings) -> Unit,
    onUserConfigChange: (UserConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF12100E),
    ) {
        val scrollState = rememberScrollState()
        // Buffer the user-config fields locally so typing doesn't trigger a
        // reconnect on every keystroke. Commit explicitly via the Apply button.
        var wsUrl by remember(userConfig.wsUrl) { mutableStateOf(userConfig.wsUrl) }
        var authToken by remember(userConfig.authToken) { mutableStateOf(userConfig.authToken) }
        var ownerName by remember(userConfig.ownerName) { mutableStateOf(userConfig.ownerName) }
        var showToken by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Settings",
                color = LineColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )

            SectionTitle("Server")
            OutlinedTextField(
                value = wsUrl,
                onValueChange = { wsUrl = it },
                label = { Text("WebSocket URL", color = MutedColor) },
                placeholder = {
                    Text("wss://your-server.tld/ws/dialog", color = MutedColor)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = authToken,
                onValueChange = { authToken = it },
                label = { Text("Auth token (optional)", color = MutedColor) },
                placeholder = { Text("bearer token", color = MutedColor) },
                singleLine = true,
                visualTransformation =
                    if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        text = if (showToken) "hide" else "show",
                        color = Accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { showToken = !showToken },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = ownerName,
                onValueChange = { ownerName = it },
                label = { Text("Owner name", color = MutedColor) },
                placeholder = { Text("owner", color = MutedColor) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
            )
            Text(
                text = "Apply",
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        onUserConfigChange(
                            userConfig.copy(
                                wsUrl = wsUrl,
                                authToken = authToken,
                                ownerName = ownerName,
                            )
                        )
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )

            SectionTitle("Voice")
            VoiceStyle.entries.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChange(current.copy(voiceStyle = s)) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = current.voiceStyle == s,
                        onClick = { onChange(current.copy(voiceStyle = s)) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Accent,
                            unselectedColor = MutedColor,
                        ),
                    )
                    Text(
                        text = s.label,
                        color = LineColor,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            SectionTitle("Dialog mode")
            DialogMode.entries.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChange(current.copy(dialogMode = m)) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(
                        selected = current.dialogMode == m,
                        onClick = { onChange(current.copy(dialogMode = m)) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Accent,
                            unselectedColor = MutedColor,
                        ),
                    )
                    Column(
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                    ) {
                        Text(text = m.label, color = LineColor, fontSize = 16.sp)
                        Text(
                            text = m.description,
                            color = MutedColor,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            SectionTitle("Vision")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChange(current.copy(visionEnabled = !current.visionEnabled)) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text(text = "Send camera frames", color = LineColor, fontSize = 16.sp)
                    Text(
                        text = if (current.visionEnabled)
                            "Object recognition, ambient look, vision tool calls"
                        else
                            "Camera is used only for face detection — no frames are sent to the LLM",
                        color = MutedColor,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = current.visionEnabled,
                    onCheckedChange = { onChange(current.copy(visionEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.45f),
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionTitle("Debug")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChange(current.copy(debugLogVisible = !current.debugLogVisible)) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text(text = "Show log overlay", color = LineColor, fontSize = 16.sp)
                    Text(
                        text = "Translucent overlay with the latest events (WS, mic, photo, vision)",
                        color = MutedColor,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = current.debugLogVisible,
                    onCheckedChange = { onChange(current.copy(debugLogVisible = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.45f),
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LineColor,
    unfocusedTextColor = LineColor,
    focusedBorderColor = Accent,
    unfocusedBorderColor = MutedColor,
    cursorColor = Accent,
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}
