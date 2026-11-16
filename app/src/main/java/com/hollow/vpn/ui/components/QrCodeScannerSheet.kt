package com.hollow.vpn.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import com.hollow.vpn.data.ServerEntity
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraQrScannerDialog(
    onQrDecoded: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var scanError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            scanError = "Camera permission is required for live QR scanning. You can still pick a QR image from your gallery."
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = decodeQrFromUri(context, uri)
            if (!decoded.isNullOrBlank()) {
                onQrDecoded(decoded)
            } else {
                scanError = "No QR code detected in the selected image."
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Scan Server QR Code",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasCameraPermission) {
                    CameraQrPreviewBox(
                        accentColor = scheme.primary,
                        onDecoded = onQrDecoded,
                        onCameraError = { msg -> scanError = msg }
                    )
                    Text(
                        text = "Point camera at a vless://, vmess://, trojan://, or ss:// QR code",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(scheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = scanError ?: "Allow camera access to scan QR codes live.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.testTag("btn_grant_camera_permission")
                            ) {
                                Text("Enable Camera")
                            }
                        }
                    }
                }

                if (scanError != null && hasCameraPermission) {
                    Text(
                        text = scanError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_pick_qr_from_gallery")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Pick QR Image from Gallery")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CameraQrPreviewBox(
    accentColor: Color,
    onDecoded: (String) -> Unit,
    onCameraError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val alreadyDecoded = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {
            }
            analyzerExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(960, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    if (!alreadyDecoded.get()) {
                                        val decoded = decodeQrFromImageProxy(imageProxy)
                                        if (!decoded.isNullOrBlank() && alreadyDecoded.compareAndSet(false, true)) {
                                            ContextCompat.getMainExecutor(ctx).execute {
                                                onDecoded(decoded)
                                            }
                                        }
                                    }
                                    imageProxy.close()
                                }
                            }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (e: Exception) {
                        onCameraError("Camera unavailable: ${e.message ?: "use gallery image"}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(164.dp)
                .border(2.5.dp, accentColor.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
        )
    }
}

private val qrReaderHints = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true
)

private fun decodeQrFromImageProxy(imageProxy: ImageProxy): String? {
    return try {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride

        val yData = if (rowStride == width) {
            data
        } else {
            ByteArray(width * height).also { compact ->
                for (row in 0 until height) {
                    val srcOffset = row * rowStride
                    val len = minOf(width, data.size - srcOffset)
                    if (len > 0) {
                        System.arraycopy(data, srcOffset, compact, row * width, len)
                    }
                }
            }
        }

        val source = PlanarYUVLuminanceSource(
            yData, width, height, 0, 0, width, height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().apply { setHints(qrReaderHints) }.decodeWithState(bitmap).text
    } catch (_: Exception) {
        null
    }
}

fun decodeQrFromUri(context: Context, uri: Uri): String? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        var sampleSize = 1
        val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        while (maxDim / sampleSize > 1200) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val result = decodeQrFromBitmap(bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
        result
    } catch (_: Exception) {
        null
    }
}

fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().apply { setHints(qrReaderHints) }.decodeWithState(binaryBitmap).text
    } catch (_: Exception) {
        null
    }
}

fun generateQrBitmap(payload: String, sizePx: Int = 512): Bitmap? {
    val clean = payload.trim()
    if (clean.isEmpty() || clean.length > 2900) return null
    return try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(clean, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ServerQrShareDialog(
    server: ServerEntity,
    onCopyPayload: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sharePayload = remember(server) {
        server.shareUri.ifBlank { server.rawConfigJson }
    }
    val qrBitmap = remember(sharePayload) {
        generateQrBitmap(sharePayload, sizePx = 560)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularCountryFlag(countryCode = server.countryCode, size = 28.dp)
                Column {
                    Text(
                        text = server.countryName,
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface
                    )
                    Text(
                        text = "${server.protocolCore} · ${server.resolvedIp.ifBlank { server.host }}:${server.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        tonalElevation = 0.dp
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR code for ${server.countryName}",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier
                                .padding(14.dp)
                                .size(220.dp)
                                .testTag("server_qr_image")
                        )
                    }
                    Text(
                        text = if (server.shareUri.isNotBlank()) {
                            "Scan this QR code with Hollow VPN or another Xray client."
                        } else {
                            "Encoded raw Xray config as a QR code."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "This config is too large for a single QR code. Use Copy Config instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCopyPayload(sharePayload) },
                enabled = sharePayload.isNotBlank(),
                modifier = Modifier.testTag("btn_copy_qr_payload")
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (server.shareUri.isNotBlank()) "Copy Share Link" else "Copy Config")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
