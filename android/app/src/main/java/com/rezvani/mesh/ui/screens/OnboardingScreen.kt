package com.rezvani.mesh.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.viewmodel.OnboardingViewModel
import kotlin.math.*

@Composable
fun OnboardingScreen(
    onEnterMesh: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState

    LaunchedEffect(uiState.step) {
        if (uiState.step == OnboardingStep.DONE) {
            onEnterMesh()
        }
    }

    Scaffold(
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Button(
                    onClick = { viewModel.enterMesh(context) },
                    enabled = !uiState.isLoading && uiState.step != OnboardingStep.DONE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.enter_mesh))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            MeshLogo(modifier = Modifier.size(200.dp))

            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = stringResource(R.string.onboarding_welcome_message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MeshLogo(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 68f,
        targetValue = 75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.6f

        drawCircle(color = Color(0xFF0F3D2A), radius = radius, center = center)

        drawCircle(
            color = Color(0xFF1E6B4E).copy(alpha = 0.3f),
            radius = pulse,
            center = center,
            style = Stroke(width = 2f)
        )

        drawArc(
            color = Color(0xFF4CAF50).copy(alpha = 0.8f),
            startAngle = rotation,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = Offset(center.x - radius - 4, center.y - radius - 4),
            size = Size((radius + 4) * 2, (radius + 4) * 2),
            style = Stroke(width = 3f)
        )

        val nodeCount = 12
        for (i in 0 until nodeCount) {
            val angle = Math.toRadians((i * 30.0 + rotation * 0.3))
            val nodeX = center.x + radius * cos(angle).toFloat()
            val nodeY = center.y + radius * sin(angle).toFloat()
            val active = sin(rotation * 0.05 + i * 0.5) > 0
            val fill = if (active) Color(0xFF4CAF50) else Color(0xFF4A3A10)
            val stroke = if (active) Color(0xFF6BFF6B) else Color(0xFF6B5A20)
            drawCircle(color = fill, radius = 4.5f, center = Offset(nodeX, nodeY))
            drawCircle(color = stroke, radius = 4.5f, center = Offset(nodeX, nodeY), style = Stroke(1.5f))
            drawCircle(color = Color(0xFF8A7A30), radius = 2f, center = Offset(nodeX, nodeY))
        }
    }
}

enum class OnboardingStep {
    WELCOME, DONE
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
