package com.lethio.macros.ui.scanner

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lethio.macros.R
import com.lethio.macros.domain.model.FoodRef
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onFoodFound: (foodId: Long, foodSource: String) -> Unit,

    onNotFound: (barcode: String, offMissing: Boolean) -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<ScanState>(ScanState.Scanning) }
    val offLookupEnabled by viewModel.offLookupEnabled.collectAsStateWithLifecycle()
    val lookupState by viewModel.lookupState.collectAsStateWithLifecycle()
    var showLookupExplainer by remember { mutableStateOf(false) }

    val onFoodRef: (FoodRef) -> Unit = { ref ->
        ref.id?.let { onFoodFound(it, ref.kind.value) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {

            TopAppBar(title = { Text(stringResource(R.string.scan_barcode)) })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.camera_permission_required),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            } else {
                when (val state = scanResult) {
                    is ScanState.Scanning -> {
                        val executor = remember { Executors.newSingleThreadExecutor() }
                        val barcodeAccepted = remember { AtomicBoolean(false) }
                        val handles = remember { CameraHandles() }

                        DisposableEffect(Unit) {
                            onDispose {
                                handles.release()
                                executor.shutdown()
                            }
                        }

                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }

                                    val options = BarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(
                                            Barcode.FORMAT_UPC_A,
                                            Barcode.FORMAT_UPC_E,
                                            Barcode.FORMAT_EAN_13,
                                            Barcode.FORMAT_EAN_8,
                                        )
                                        .build()

                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    try {
                                        executor.execute {
                                            val scanner = BarcodeScanning.getClient(options)

                                            ContextCompat.getMainExecutor(ctx).execute {
                                                analysis.setAnalyzer(
                                                    executor,
                                                    barcodeAnalyzer(scanner, barcodeAccepted) { barcode ->
                                                        analysis.clearAnalyzer()
                                                        scanResult = ScanState.Found(barcode)
                                                        viewModel.lookupBarcode(barcode) { ref ->
                                                            if (ref?.id != null) {
                                                                onFoodFound(ref.id!!, ref.kind.value)
                                                            } else {
                                                                scanResult = ScanState.NotFound(barcode)
                                                            }
                                                        }
                                                    },
                                                )

                                                if (!handles.attach(cameraProvider, analysis, scanner)) {
                                                    return@execute
                                                }

                                                try {
                                                    cameraProvider.unbindAll()
                                                    cameraProvider.bindToLifecycle(
                                                        lifecycleOwner,
                                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                                        preview,
                                                        analysis,
                                                    )
                                                } catch (e: Exception) {
                                                    Log.e("Scanner", "Camera bind failed", e)
                                                }
                                            }
                                        }
                                    } catch (e: RejectedExecutionException) {

                                        Log.d("Scanner", "Screen torn down before the scanner was built", e)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },

                            modifier = Modifier
                                .fillMaxSize()
                                .clearAndSetSemantics {},
                        )

                        Text(
                            stringResource(R.string.point_camera_at_barcode),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    is ScanState.Found -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {

                            Text(
                                stringResource(R.string.looking_up_barcode, state.barcode),
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                    }

                    is ScanState.NotFound -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.barcode_not_found),
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                state.barcode,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(16.dp))
                            when {

                                lookupState == LookupState.RUNNING -> {
                                    val runningCd = stringResource(R.string.off_lookup_running)
                                    CircularProgressIndicator(
                                        Modifier
                                            .height(32.dp)
                                            .semantics {
                                                contentDescription = runningCd
                                                liveRegion = LiveRegionMode.Polite
                                            },
                                    )
                                }

                                offLookupEnabled ->
                                    Button(onClick = { viewModel.lookUpOnline(state.barcode, onFoodRef) }) {
                                        Text(stringResource(R.string.look_up_online))
                                    }

                                else ->
                                    TextButton(onClick = { showLookupExplainer = true }) {
                                        Text(stringResource(R.string.off_lookup_offer))
                                    }
                            }

                            val lookupMessage = when (lookupState) {
                                LookupState.NOTHING_FOUND, LookupState.NOT_USABLE -> R.string.off_lookup_nothing_found
                                LookupState.UNAVAILABLE -> R.string.off_lookup_unavailable
                                else -> null
                            }
                            lookupMessage?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(it),
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    onNotFound(
                                        state.barcode,
                                        lookupState.offConfirmedMissing,
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.add_manually))
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                viewModel.resetLookup()
                                scanResult = ScanState.Scanning
                            }) {
                                Text(stringResource(R.string.scan_again))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLookupExplainer) {
        AlertDialog(
            onDismissRequest = { showLookupExplainer = false },
            title = { Text(stringResource(R.string.off_lookup_title)) },
            text = { Text(stringResource(R.string.off_lookup_explainer)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.enableOffLookup()
                    showLookupExplainer = false
                }) {
                    Text(stringResource(R.string.off_lookup_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLookupExplainer = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

sealed class ScanState {
    data object Scanning : ScanState()
    data class Found(val barcode: String) : ScanState()
    data class NotFound(val barcode: String) : ScanState()
}

private class CameraHandles {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var scanner: BarcodeScanner? = null
    private var released = false

    fun attach(
        provider: ProcessCameraProvider,
        analysis: ImageAnalysis,
        scanner: BarcodeScanner,
    ): Boolean {
        if (released) {
            scanner.close()
            return false
        }
        this.provider = provider
        this.analysis = analysis
        this.scanner = scanner
        return true
    }

    fun release() {
        released = true
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        scanner?.close()
        analysis = null
        provider = null
        scanner = null
    }
}

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
private fun barcodeAnalyzer(
    scanner: BarcodeScanner,
    accepted: AtomicBoolean,
    onBarcode: (String) -> Unit,
) = ImageAnalysis.Analyzer { imageProxy ->
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull()?.rawValue
                if (barcode != null && accepted.compareAndSet(false, true)) {
                    onBarcode(barcode)
                }
            }
            .addOnFailureListener { e ->
                Log.e("Scanner", "Barcode decode failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
