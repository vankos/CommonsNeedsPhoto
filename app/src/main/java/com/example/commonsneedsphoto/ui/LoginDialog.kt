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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.network.TwoFactorMethod

@Composable
fun LoginDialog(
    state: LoginUiState,
    onLogin: (String, String) -> Unit,
    onVerify: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var loginName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = { if (!state.isLoggingIn) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (state.requiresTwoFactor) {
                        R.string.login_two_factor_title
                    } else {
                        R.string.login_title
                    },
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (state.requiresTwoFactor) {
                    Text(
                        text = stringResource(
                            when (state.twoFactorMethod) {
                                TwoFactorMethod.EMAIL -> R.string.login_two_factor_email_hint
                                else -> R.string.login_two_factor_totp_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.login_two_factor_code)) },
                        singleLine = true,
                        enabled = !state.isLoggingIn,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (state.twoFactorMethod == TwoFactorMethod.EMAIL) {
                                KeyboardType.Text
                            } else {
                                KeyboardType.NumberPassword
                            },
                        ),
                    )
                } else {
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
                        // Lets the system autofill service and password managers
                        // recognise this as the username half of a login form.
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Username },
                        label = { Text(stringResource(R.string.username)) },
                        placeholder = { Text(stringResource(R.string.login_name_hint)) },
                        singleLine = true,
                        enabled = !state.isLoggingIn,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                        label = { Text(stringResource(R.string.bot_password)) },
                        singleLine = true,
                        enabled = !state.isLoggingIn,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!state.requiresTwoFactor) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.privacy_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (state.requiresTwoFactor) {
                        onVerify(code)
                    } else {
                        onLogin(loginName, password)
                    }
                },
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
                    Text(
                        stringResource(
                            if (state.requiresTwoFactor) {
                                R.string.login_verify
                            } else {
                                R.string.login_action
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (state.requiresTwoFactor) {
                        onBack()
                    } else {
                        onDismiss()
                    }
                },
                enabled = !state.isLoggingIn,
            ) {
                Text(
                    stringResource(
                        if (state.requiresTwoFactor) {
                            R.string.login_back
                        } else {
                            R.string.cancel
                        },
                    ),
                )
            }
        },
    )
}

private const val BOT_PASSWORDS_URL = "https://commons.wikimedia.org/wiki/Special:BotPasswords"