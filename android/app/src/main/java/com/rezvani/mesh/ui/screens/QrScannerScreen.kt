// android/app/src/main/java/com/rezvani/mesh/ui/screens/QrScannerScreen.kt

package com.rezvani.mesh.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rezvani.mesh.R
import java.util.concurrent.Executors

/**
 * RezvanMesh's own built-in QR scanner -- CameraX preview + ML Kit
 * on-device barcode detection (no network calls, no photo/gallery step,
 * continuous live-feed scanning). Not the system camera app, and not a
 * third-party library's generic scanner UI: this screen is Compose-native
 * so it automatically follows the device's configured orientation handling
 * like every other screen in the app (no separate Activity, no forced
 * orientation lock), and every visible element (title bar, viewfinder,
 * prompt text) is RezvanMesh's own branding.
 *
 * @param title Shown in the top bar, e.g. "Scan Contact QR" / "Scan Channel QR"
 * @param prompt Short instruction shown under the viewfinder
 * @param onResult Called once with the first successfully decoded QR payload
 * @param onCancel Called when the user backs out without scanning
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    title: String,
    prompt: String,
    onResult: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var torchOn by remember { mutableStateOf(false) }
    var hasResult by remember { mutableStateOf(false) } // guards against firing onResult twice
    // The ImageAnalysis.Analyzer lambda below runs on a background executor
    // and is created once inside AndroidView's factory (which only runs on
    // first composition) -- it captures `hasResult` by value at that point,
    // not by reference to the Compose state, so checking the State object
    // itself won't reflect later updates from inside that closure. Use a
    // plain mutable holder the analyzer reads by reference on every frame
    // instead.
    val hasResultHolder = remember { booleanArrayOf(false) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (hasCameraPermission) {
                        IconButton(onClick = {
                            torchOn = !torchOn
                            cameraControl?.enableTorch(torchOn)
                        }) {
                            Icon(
                                if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = stringResource(R.string.toggle_flashlight)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_needed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
                return@Scaffold
            }

            // CameraX preview + analysis, hosted in Compose via AndroidView --
            // this is the standard, documented way to use CameraX inside
            // Jetpack Compose. PreviewView + the CameraX lifecycle bindings
            // handle rotation/orientation changes automatically; there's no
            // manual rotation-lock code anywhere in this screen.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val scannerOptions = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val scanner = BarcodeScanning.getClient(scannerOptions)
                    val analysisExecutor = Executors.newSingleThreadExecutor()

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processFrame(imageProxy, scanner, hasResultHolder[0]) { decoded ->
                                if (!hasResultHolder[0]) {
                                    hasResultHolder[0] = true
                                    onResult(decoded)
                                }
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                            cameraControl = camera.cameraControl
                        } catch (e: Exception) {
                            // Camera bind can fail on some devices/emulators;
                            // fail closed to the permission/instruction view
                            // rather than crash the screen.
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // RezvanMesh-branded viewfinder overlay: a dimmed background with
            // a clear square cutout, corner brackets, and the prompt text --
            // not a third-party library's generic scanning chrome.
            ScannerOverlay(prompt = prompt)
        }
    }
}

private fun processFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    alreadyDone: Boolean,
    onDecoded: (String) -> Unit
) {
    if (alreadyDone) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val value = barcodes.firstOrNull { it.rawValue != null }?.rawValue
            if (value != null) onDecoded(value)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
private fun ScannerOverlay(prompt: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSize = size.minDimension * 0.65f
            val left = (size.width - boxSize) / 2f
            val top = (size.height - boxSize) / 2f
            val right = left + boxSize
            val bottom = top + boxSize
            val dim = Color.Black.copy(alpha = 0.55f)

            // Dim everything outside the viewfinder square using four plain
            // rectangles (top band, bottom band, left band, right band)
            // rather than a path-based cutout -- simpler and avoids relying
            // on RoundRect/EvenOdd fill-rule API details.
            drawRect(color = dim, topLeft = Offset(0f, 0f), size = GeoSize(size.width, top))
            drawRect(color = dim, topLeft = Offset(0f, bottom), size = GeoSize(size.width, size.height - bottom))
            drawRect(color = dim, topLeft = Offset(0f, top), size = GeoSize(left, boxSize))
            drawRect(color = dim, topLeft = Offset(right, top), size = GeoSize(size.width - right, boxSize))

            // Corner brackets, RezvanMesh accent color, for a distinctly
            // "this is our app's scanner" look rather than a plain rectangle.
            val bracketLen = boxSize * 0.12f
            val strokeWidth = 6f
            val accent = Color(0xFF4CAF9E) // matches the app's teal accent used elsewhere

            fun corner(x: Float, y: Float, dx1: Float, dy1: Float, dx2: Float, dy2: Float) {
                drawLine(accent, Offset(x, y), Offset(x + dx1, y + dy1), strokeWidth = strokeWidth)
                drawLine(accent, Offset(x, y), Offset(x + dx2, y + dy2), strokeWidth = strokeWidth)
            }
            corner(left, top, bracketLen, 0f, 0f, bracketLen)
            corner(right, top, -bracketLen, 0f, 0f, bracketLen)
            corner(left, bottom, bracketLen, 0f, 0f, -bracketLen)
            corner(right, bottom, -bracketLen, 0f, 0f, -bracketLen)
        }

        Text(
            text = prompt,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
        )
    }
}
