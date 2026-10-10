package com.lethio.macros.ui.scanner

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import zxingcpp.BarcodeReader
import java.util.concurrent.Executor
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
import com.lethio.macros.R
import com.lethio.macros.domain.model.FoodRef
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onFoodFound: (foodId: Long, foodSource: String) -> Unit,
    /**
     * @param barcode the code that missed, so the product entered next is stored against it and
     *   found by scanning it again.
     * @param offMissing true only when Open Food Facts was actually asked and answered "not
     *   found". Gates the contribution offer: a barcode nobody checked may well be one Open Food
     *   Facts already has.
     */
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

    // A looked-up product is saved as a custom food first, so it reaches log entry like any other.
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
            // No back arrow: Scan is a bottom-nav tab.
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

                                    val options = retailBarcodeOptions()

                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    // Constructing the reader loads a native library (disk I/O), so build it
                                    // on the analyzer's executor and return to the main thread only to bind.
                                    try {
                                        executor.execute {
                                            val scanner = BarcodeReader(options)
                                            val mainExecutor = ContextCompat.getMainExecutor(ctx)

                                            mainExecutor.execute {
                                                analysis.setAnalyzer(
                                                    executor,
                                                    barcodeAnalyzer(scanner, barcodeAccepted, mainExecutor) { barcode ->
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

                                                // Record the handles before binding so a failed bind can still be
                                                // undone. False means the screen was torn down in the meantime.
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
                                        // The screen was torn down first; nothing was built.
                                        Log.d("Scanner", "Screen torn down before the scanner was built", e)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            // Not a focus stop: a preview has no text, state or action. The events are
                            // announced by the live regions below.
                            modifier = Modifier
                                .fillMaxSize()
                                .clearAndSetSemantics {},
                        )

                        // The hint takes its height first and the frame centres in the rest, so they
                        // cannot overlap at large text sizes.
                        Column(Modifier.fillMaxSize()) {
                            ScanGuide(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )

                            // On a surface, because text over live video has whatever contrast the scene gives.
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            ) {
                                Text(
                                    stringResource(R.string.scan_guide_hint),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    is ScanState.Found -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Announces the decode, which a sighted user sees as the preview being replaced.
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

                            // The online lookup: only when enabled in Settings, and only on a tap.
                            Spacer(Modifier.height(16.dp))
                            when {
                                // Labelled so the network wait is announced, not an unnamed spinner.
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

                            // "Not found" is final; "no answer" offers a retry.
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

    // Shown before anything is sent; it says exactly what leaves the phone.
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

/**
 * The camera and scanner handles for one visit to the screen. They are created in an asynchronous
 * callback but released from a [DisposableEffect], and the screen may leave composition before the
 * callback fires; holding them here covers that gap and keeps them out of Compose state. Both ends
 * run on the main thread, so [released] needs no synchronisation.
 */
private class CameraHandles {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var scanner: BarcodeReader? = null
    private var released = false

    /**
     * Takes ownership of the handles. False when teardown has already run; the caller must not
     * bind. The zxing-cpp reader holds no native handle to close.
     */
    fun attach(
        provider: ProcessCameraProvider,
        analysis: ImageAnalysis,
        scanner: BarcodeReader,
    ): Boolean {
        if (released) {
            return false
        }
        this.provider = provider
        this.analysis = analysis
        this.scanner = scanner
        return true
    }

    /** Clears the analyzer first, so no frame arrives after the screen is gone, then unbinds. */
    fun release() {
        released = true
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analysis = null
        provider = null
        scanner = null
    }
}

/**
 * A level frame over the preview. zxing-cpp is fastest when the bars cross the frame's width and
 * only finds a sideways code on its slower rotation pass, so the guide shows the orientation that
 * works first time. It guides, it does not crop. No semantics: the hint text carries the
 * instruction for TalkBack.
 */
@Composable
private fun ScanGuide(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val frameWidth = size.width * 0.84f
        // Wider than a barcode's ~1.4:1 so a pack held at an angle still fits.
        val frameHeight = minOf(frameWidth * 0.45f, size.height * 0.7f)
        val topLeft = Offset((size.width - frameWidth) / 2f, (size.height - frameHeight) / 2f)
        val corner = CornerRadius(16.dp.toPx())
        val frame = RoundRect(Rect(topLeft, Size(frameWidth, frameHeight)), corner)

        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(frame)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.45f))
        val outline = Path().apply { addRoundRect(frame) }
        drawPath(outline, Color.Black.copy(alpha = 0.55f), style = Stroke(width = 6.dp.toPx()))
        drawPath(outline, Color.White, style = Stroke(width = 3.dp.toPx()))
    }
}

/**
 * The four retail formats food packages carry. No QR: it carries arbitrary text and no food encodes
 * its GTIN that way; `OffLookupService` validates the barcode independently as well. `tryRotate`
 * covers packs held sideways, `tryHarder` worn or curved labels. HRI text mode yields the digit
 * strings the barcode lookup matches on (12 for UPC-A, 8 for UPC-E). Shared with
 * `BarcodeDecodeTest`, so the test uses the shipped options.
 */
internal fun retailBarcodeOptions() = BarcodeReader.Options(
    formats = setOf(
        BarcodeReader.Format.UPC_A,
        BarcodeReader.Format.UPC_E,
        BarcodeReader.Format.EAN_13,
        BarcodeReader.Format.EAN_8,
    ),
    tryHarder = true,
    tryRotate = true,
)

/**
 * The frame analyzer for one scanning pass. Decodes synchronously on the analyzer's executor and
 * posts the result to [mainExecutor]. [accepted] makes the first barcode the only one; clearing the
 * analyzer is [onBarcode]'s job.
 */
private fun barcodeAnalyzer(
    scanner: BarcodeReader,
    accepted: AtomicBoolean,
    mainExecutor: Executor,
    onBarcode: (String) -> Unit,
) = ImageAnalysis.Analyzer { imageProxy ->
    try {
        val barcode = scanner.read(imageProxy).firstOrNull { it.error == null }?.text
        if (!barcode.isNullOrEmpty() && accepted.compareAndSet(false, true)) {
            mainExecutor.execute { onBarcode(barcode) }
        }
    } catch (e: Exception) {
        Log.e("Scanner", "Barcode decode failed", e)
    } finally {
        imageProxy.close()
    }
}
