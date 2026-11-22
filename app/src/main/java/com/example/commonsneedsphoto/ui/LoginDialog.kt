package com.example.commonsneedsphoto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.commonsneedsphoto.R

@Composable
fun LoginDialog(
    state: LoginUiState,
    onLogin: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loginName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = { if (!state.isLoggingIn) onDismiss() },
        title = { Text(stringResource(R.string.login_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.login_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { uriHandler.openUri(BOT_PASSWORDS_URL) },
                    enabled = !state.isLoggingIn,
                ) {
                    Text(stringResource(R.string.login_open_bot_passwords))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = loginName,
                    onValueChange = { loginName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.username)) },
                    placeholder = { Text("name@bot") },
                    singleLine = true,
                    enabled = !state.isLoggingIn,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.bot_password)) },
                    singleLine = true,
                    enabled = !state.isLoggingIn,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.privacy_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLogin(loginName, password) },
                enabled = !state.isLoggingIn,
            ) {
                if (state.isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.login_in_progress))
                } else {
                    Text(stringResource(R.string.login_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isLoggingIn) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private const val BOT_PASSWORDS_URL = "https://commons.wikimedia.org/wiki/Special:BotPasswords"
