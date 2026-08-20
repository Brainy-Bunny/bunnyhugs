package com.ssbmedia.twogether.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.ui.moments.GalleryImportHost
import com.ssbmedia.twogether.util.ImageDownscaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Suspends until CameraX's own async provider future resolves, on the main executor (required by
 * bindToLifecycle). No camera-lifecycle-ktx dependency in this project, so this is the small manual
 * equivalent of that library's own `ProcessCameraProvider.awaitInstance(context)`. */
private suspend fun awaitCameraProvider(context: android.content.Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cont.resume(future.get())
        }, ContextCompat.getMainExecutor(context))
    }

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

@Composable
fun CameraScreen(onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    // Item 6 (permission-denied recovery): whether the request launcher has come back with a result at
    // least once - see CameraPermissionState's own doc for why this has to be tracked separately from
    // ActivityCompat.shouldShowRequestPermissionRationale alone.
    var hasRequestedPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasRequestedPermission = true
        hasPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Item 1: lens facing is now real hoisted state, not a hardcoded CameraSelector - flipping it below
    // re-runs the LaunchedEffect(lensFacing) binder further down.
    var lensFacing by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    // Item 1/2/3: the bound Camera handle (previously discarded), which is what enableTorch/
    // setZoomRatio/startFocusAndMetering all hang off. Re-created every time lensFacing changes.
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // The single PreviewView instance for this screen's whole lifetime - the AndroidView factory below
    // now only ever returns this same remembered instance, so re-binding on lensFacing change doesn't
    // need to recreate (and doesn't lose) the underlying SurfaceView.
    val previewView = remember { PreviewView(context) }

    var showSaved by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    // Item 7: the just-taken photo, held here for the Retake/Keep review step instead of being written
    // straight to the DB. Null means "no pending capture to review" (normal live-preview state).
    var pendingReview by remember { mutableStateOf<File?>(null) }
    // Item 3: brief tap-to-focus indicator position, cleared automatically a moment later.
    var focusIndicatorAt by remember { mutableStateOf<Offset?>(null) }
    // Tracked so the DisposableEffect below can release the camera when this screen leaves
    // composition, regardless of whether the AndroidView factory (which runs once, outside Compose's
    // normal recomposition/disposal lifecycle) already finished its async bind by then.
    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // CameraScreen binds via bindToLifecycle(lifecycleOwner) using the single Activity-level
    // lifecycle owner (this is a single-Activity app) - so the camera stays bound (and the hardware
    // keeps streaming, privacy-indicator light lit, battery draining) for as long as the Activity is
    // resumed, NOT just while this screen is on top. Previously unbindAll() was only ever called right
    // before the NEXT bind, so navigating back to Home left the camera hardware bound and streaming
    // until this screen was revisited or the process died. Releasing it here on dispose (i.e. on
    // leaving this screen) fixes that.
    DisposableEffect(Unit) {
        onDispose {
            boundCameraProvider?.unbindAll()
        }
    }

    // Item 1: (re)binds every time lensFacing flips, onto the single remembered previewView above -
    // this is what actually makes the flip button work, versus the old bind-once-in-a-factory shape.
    LaunchedEffect(lensFacing) {
        if (!hasPermission) return@LaunchedEffect
        torchOn = false // a fresh Camera handle after a rebind has no memory of the old torch state
        val cameraProvider = try {
            awaitCameraProvider(context)
        } catch (e: Exception) {
            return@LaunchedEffect
        }
        boundCameraProvider = cameraProvider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder().build()
        imageCapture = capture
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, lensFacing, preview, capture)
        } catch (e: Exception) {
            // Camera bind failed (e.g. this device has no front camera to flip to); leave preview
            // blank/stale, user can still cancel or flip back.
            camera = null
        }
    }

    // Item 5 (volume-button shutter): MainActivity's dispatchKeyEvent has no direct handle to this
    // composable's own capture function, so it nudges via the shared AppEvents bus (same pattern this
    // app already uses everywhere else an Activity/service needs to reach into the current UI).
    LaunchedEffect(Unit) {
        AppEvents.cameraShutterRequests.collect {
            if (hasPermission && pendingReview == null && !isSaving) {
                isSaving = true
                capturePhotoInternal(
                    context = context,
                    imageCapture = imageCapture,
                    onCaptured = { file ->
                        isSaving = false
                        pendingReview = file
                    },
                    onError = { message ->
                        isSaving = false
                        captureError = message
                    }
                )
            }
        }
    }

    // "Choose from gallery instead" reuses the exact same pick -> copy-to-local-storage ->
    // date-assignment flow MomentsScreen's gallery FAB triggers (see GalleryImportFlow.kt) - a
    // gallery-backfilled Moment is a legitimate alternative to a live capture right from this screen,
    // not just from Moments. onImported reuses the existing showSaved/LaunchedEffect(showSaved) ->
    // onSaved() path below exactly like a successful live capture does.
    GalleryImportHost(onImported = { showSaved = true }) { launchGalleryPicker ->
    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Item 3: tap-to-focus + pinch-to-zoom, layered as a transparent overlay ON TOP of the
            // AndroidView above (Box children stack with later ones on top) so it intercepts touches
            // before the native PreviewView would ever see them - it has no gesture handling of its own
            // to conflict with.
            if (pendingReview == null && !showSaved) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(camera) {
                            detectTapGestures { offset ->
                                val cam = camera ?: return@detectTapGestures
                                val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(
                                    point,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                try {
                                    cam.cameraControl.startFocusAndMetering(action)
                                    focusIndicatorAt = offset
                                } catch (e: Exception) {
                                    // Camera not in a state to focus right now (mid-rebind, etc) - ignore the tap.
                                }
                            }
                        }
                        .pointerInput(camera) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                val cam = camera ?: return@detectTransformGestures
                                val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                                val newZoom = (zoomState.zoomRatio * zoomChange)
                                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                                cam.cameraControl.setZoomRatio(newZoom)
                            }
                        }
                )
            }

            // Item 3: brief ring at the last tap-to-focus point, matching where the user tapped.
            focusIndicatorAt?.let { offset ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offset.x.toInt() - 28.dp.roundToPx(), offset.y.toInt() - 28.dp.roundToPx()) }
                        .size(56.dp)
                        .border(2.dp, Color.White, CircleShape)
                )
                LaunchedEffect(offset) {
                    kotlinx.coroutines.delay(600)
                    focusIndicatorAt = null
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("We need camera access to snap a photo together 📷", style = MaterialTheme.typography.bodyLarge)
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    val activity = context as? Activity
                    val shouldShowRationale = activity?.let {
                        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                    } ?: false
                    val recoveryState = CameraPermissionState.recoveryState(hasRequestedPermission, shouldShowRationale)
                    when (recoveryState) {
                        PermissionRecoveryState.NOT_YET_ASKED -> {
                            // System dialog is already up or about to be; nothing to show yet.
                        }
                        PermissionRecoveryState.CAN_REQUEST_AGAIN -> {
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant access")
                            }
                        }
                        PermissionRecoveryState.PERMANENTLY_DENIED -> {
                            Text(
                                "Camera access was permanently denied. Enable it from the app's system settings.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Button(onClick = { openAppSettings(context) }) {
                                Text("Open Settings")
                            }
                        }
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Cancel")
                    }
                }
            }
        }

        if (showSaved) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Saved! 💛", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        captureError?.let { message ->
            Box(modifier = Modifier.fillMaxSize().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2500)
                captureError = null
            }
        }

        // Item 4: close button, top-left, standard camera-UI placement - calls the previously-dead
        // onCancel param. Hidden once a capture is actually being reviewed/saved so it can't be
        // mistaken for "discard the whole session"; Retake/Cancel below cover that instead.
        if (!showSaved && pendingReview == null) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .padding(16.dp)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .align(Alignment.TopStart)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        // Item 1 + 2: flip and flash/torch controls, top-right - only meaningful while the live preview
        // is showing (not during permission-denied, review, or saved states).
        if (hasPermission && !showSaved && pendingReview == null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                if (hasFlash) {
                    IconButton(
                        onClick = {
                            val cam = camera ?: return@IconButton
                            val next = !torchOn
                            cam.cameraControl.enableTorch(next)
                            torchOn = next
                        },
                        modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = if (torchOn) "Turn flash off" else "Turn flash on",
                            tint = Color.White
                        )
                    }
                }
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                    },
                    modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Flip camera", tint = Color.White)
                }
            }
        }

        // Item 7: post-capture review - shown full-screen instead of writing straight to the DB and
        // auto-navigating away. Only "Keep" commits the write (same downscale + session-tag + sync-kick
        // logic as before); "Retake" just discards the file and drops back to the live preview.
        pendingReview?.let { file ->
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = file,
                    contentDescription = "Photo preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                if (isSaving) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(onClick = {
                            file.delete()
                            pendingReview = null
                        }) { Text("Retake") }
                        Button(onClick = {
                            isSaving = true
                            scope.launch {
                                // MAJOR fix (kept intact from before): downscale before this Moment is
                                // ever queued for BLE sync - a real camera JPEG (3-5MB) essentially never
                                // finishes transferring at real BLE throughput otherwise. See
                                // ImageDownscaler's doc for the "downscaled copy only" choice.
                                val finalFile = withContext(Dispatchers.IO) {
                                    ImageDownscaler.downscaleIfNeeded(file)
                                }
                                val openSession = ServiceLocator.sessionRepository.getOpenSession()
                                ServiceLocator.momentRepository.add(finalFile.absolutePath, openSession?.id)
                                isSaving = false
                                pendingReview = null
                                showSaved = true
                                // A new photo only auto-syncs out via the next apart->together
                                // transition otherwise - if we're already together right now (the
                                // common case, since this screen is usually opened while together),
                                // kick a sync immediately so it doesn't wait for a disconnect/reconnect
                                // cycle that might not happen again this session.
                                if (ServiceLocator.proximityStateStore.current().isTogether) {
                                    AppEvents.requestManualSync()
                                }
                            }
                        }) { Text("Keep") }
                    }
                }
            }
        }

        if (hasPermission && !showSaved && pendingReview == null) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.BottomCenter) {
                if (isSaving) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            isSaving = true
                            capturePhotoInternal(
                                context = context,
                                imageCapture = imageCapture,
                                onCaptured = { file ->
                                    isSaving = false
                                    pendingReview = file
                                },
                                onError = { message ->
                                    isSaving = false
                                    captureError = message
                                }
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Text("📸")
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        TextButton(onClick = launchGalleryPicker) {
                            Text("Choose from gallery instead")
                        }
                    }
                    }
                }
            }
        }
    }
    }

    LaunchedEffect(showSaved) {
        if (showSaved) {
            kotlinx.coroutines.delay(1200)
            onSaved()
        }
    }

    // Note: capturePhotoInternal's isSaving toggling happens at each call site above (FAB click and the
    // volume-shutter collector) rather than inside the helper itself, since the helper is a plain
    // (non-Composable) function and can't hold Compose state.
}

/**
 * Fires an ImageCapture.takePicture() call and reports back via [onCaptured] or [onError] once CameraX's
 * own callback resolves - deliberately not `suspend` since ImageCapture.OnImageSavedCallback is itself
 * callback-based, not coroutine-based, and both callbacks here need to land on Compose state regardless
 * of whether takePicture() itself could even be started (no bound [imageCapture] yet reports [onError]
 * synchronously; everything past that point reports asynchronously once CameraX finishes the write).
 * Extracted out of the composable body so both the shutter FAB and the volume-button-shutter collector
 * share the exact same filename/collision-avoidance logic and error handling instead of duplicating it.
 */
private fun capturePhotoInternal(
    context: android.content.Context,
    imageCapture: ImageCapture?,
    onCaptured: (File) -> Unit,
    onError: (String) -> Unit
) {
    val capture = imageCapture
    if (capture == null) {
        onError("Camera isn't ready yet - try again in a moment")
        return
    }
    // BUG fix (kept intact from before): second-granularity timestamps meant two captures on THIS
    // device within the same wall-clock second would silently collide onto one filename - the second
    // capture's bytes would overwrite the first's real photo file, while both still had their own
    // separate Moment DB rows (one now pointing at the wrong image, or a 404'd thumbnail if the first
    // row's file got replaced by the second's content). Millisecond precision plus a short random
    // suffix makes this collision-proof regardless of how close together two captures land.
    val fileName = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date()) +
        "_" + java.util.UUID.randomUUID().toString().take(6)
    val outputDir = File(context.filesDir, "moments").apply { mkdirs() }
    val outputFile = File(outputDir, "$fileName.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onCaptured(outputFile)
            }

            override fun onError(exception: ImageCaptureException) {
                // Previously this reset only the internal saving flag with no user-visible feedback at
                // all - a failed capture (storage full, hardware error) looked identical to a silent
                // no-op tap.
                onError("Couldn't save the photo - try again")
            }
        }
    )
}
