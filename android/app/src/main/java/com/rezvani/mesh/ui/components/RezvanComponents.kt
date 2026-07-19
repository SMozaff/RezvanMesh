package com.rezvani.mesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rezvani.mesh.R

@Composable
fun PowerStateIndicator(
    powerState: PowerState = PowerState.BALANCED,
    modifier: Modifier = Modifier
) {
    val (color, labelRes) = when (powerState) {
        PowerState.EMERGENCY -> MaterialTheme.colorScheme.error to R.string.power_emergency
        PowerState.ACTIVE -> MaterialTheme.colorScheme.primary to R.string.power_active
        PowerState.BALANCED -> MaterialTheme.colorScheme.tertiary to R.string.power_balanced
        PowerState.POWER_SAVER -> Color(0xFFFFA000) to R.string.power_saver
        PowerState.MINIMAL -> MaterialTheme.colorScheme.secondary to R.string.power_minimal
        PowerState.HIBERNATION -> MaterialTheme.colorScheme.outline to R.string.power_hibernation
        PowerState.DEAD -> MaterialTheme.colorScheme.errorContainer to R.string.power_dead
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

enum class PowerState {
    EMERGENCY, ACTIVE, BALANCED, POWER_SAVER, MINIMAL, HIBERNATION, DEAD
}

@Composable
fun TrustLevelBadge(
    trustLevel: Int,
    modifier: Modifier = Modifier
) {
    val (textRes, containerColor, contentColor) = when (trustLevel) {
        0 -> Triple(
            R.string.trust_verified,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        1 -> Triple(
            R.string.trust_known,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> Triple(
            R.string.trust_blocked,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun PasswordInputDialog(
    channelName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.join_channel, channelName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.enter_channel_password),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.password)) },
                    visualTransformation = if (showPassword)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword)
                                    Icons.Default.Visibility
                                else
                                    Icons.Default.VisibilityOff,
                                contentDescription = if (showPassword)
                                    stringResource(R.string.hide_password)
                                else
                                    stringResource(R.string.show_password)
                            )
                        }
                    },
                    singleLine = true,
                    isError = errorMessage != null
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(password) },
                        enabled = password.isNotBlank()
                    ) {
                        Text(stringResource(R.string.join))
                    }
                }
            }
        }
    }
}

@Composable
fun EmergencyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.emergency_button).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SeverityPicker(
    selectedLevel: Int,
    onLevelSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val levels = listOf(
        1 to R.string.severity_advisory,
        2 to R.string.severity_watch,
        3 to R.string.severity_warning,
        4 to R.string.severity_critical,
        5 to R.string.severity_emergency
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.select_severity),
            style = MaterialTheme.typography.titleMedium
        )

        levels.forEach { (level, labelRes) ->
            val isSelected = selectedLevel == level
            val containerColor = when (level) {
                1 -> MaterialTheme.colorScheme.primaryContainer
                2 -> MaterialTheme.colorScheme.secondaryContainer
                3 -> Color(0xFFFFF3E0)
                4 -> Color(0xFFFFE0E0)
                5 -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLevelSelected(level) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) containerColor else MaterialTheme.colorScheme.surface,
                border = if (isSelected)
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else
                    null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onLevelSelected(level) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "$level - ${stringResource(labelRes)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingOverlay(
    message: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector = Icons.Default.Info,
    message: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (actionText != null && onAction != null) {
                Button(onClick = onAction) {
                    Text(actionText)
                }
            }
        }
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = stringResource(R.string.confirm),
    cancelText: String = stringResource(R.string.cancel),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive)
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                else
                    ButtonDefaults.buttonColors()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        }
    )
}

@Composable
fun Modifier.clickableWithoutRipple(
    onClick: () -> Unit
): Modifier = this.clickable(
    indication = null,
    interactionSource = remember { MutableInteractionSource() }
) {
    onClick()
}
