/*
 * portage-recv (importer) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.recv.ui

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live QR viewfinder. CameraX [PreviewView] hosted via [AndroidView] with an [ImageAnalysis]
 * stage feeding ML Kit's barcode scanner (QR-only). The first decoded `rawValue` fires
 * [onResult] exactly once — a [AtomicBoolean] latch debounces the repeats ML Kit emits while
 * the same code stays in frame. Bound to the composition's [LocalLifecycleOwner]; the analysis
 * executor and scanner are released on dispose so nothing leaks the camera.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Single-thread analysis executor + a one-shot latch, stable across recompositions.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val provider = providerFuture.get().also { cameraProvider = it }

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(analysisExecutor) { imageProxy ->
                        processFrame(scanner, imageProxy, delivered, onResult)
                    }
                }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Run one analysis frame through ML Kit. Always closes the [imageProxy] when done (failing to
 * close stalls the pipeline). Fires [onResult] only on the first non-blank QR value.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalGetImage::class)
private fun processFrame(
    scanner: BarcodeScanner,
    imageProxy: androidx.camera.core.ImageProxy,
    delivered: AtomicBoolean,
    onResult: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val value = barcodes.firstOrNull()?.rawValue
            if (!value.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                onResult(value)
            }
        }
        .addOnCompleteListener { imageProxy.close() }
}
