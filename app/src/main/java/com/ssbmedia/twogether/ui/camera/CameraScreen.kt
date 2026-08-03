package com.ssbmedia.twogether.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.events.AppEvents
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

@Composable
fun CameraScreen(onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var showSaved by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        boundCameraProvider = cameraProvider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder().build()
                        imageCapture = capture
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        } catch (e: Exception) {
                            // Camera bind failed; leave preview blank, user can cancel.
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("We need camera access to snap a photo together 📷")
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

        if (hasPermission && !showSaved) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.BottomCenter) {
                if (isSaving) {
                    CircularProgressIndicator()
                } else {
                    FloatingActionButton(
                        onClick = {
                            val capture = imageCapture ?: return@FloatingActionButton
                            isSaving = true
                            val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val outputDir = File(context.filesDir, "moments").apply { mkdirs() }
                            val outputFile = File(outputDir, "$fileName.jpg")
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                            capture.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        scope.launch {
                                            val openSession = ServiceLocator.sessionRepository.getOpenSession()
                                            ServiceLocator.momentRepository.add(outputFile.absolutePath, openSession?.id)
                                            isSaving = false
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
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        // Previously this reset only the internal saving flag with no
                                        // user-visible feedback at all - a failed capture (storage full,
                                        // hardware error) looked identical to a silent no-op tap.
                                        isSaving = false
                                        captureError = "Couldn't save the photo - try again"
                                    }
                                }
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Text("📸")
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
}
