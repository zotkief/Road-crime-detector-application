package com.example.roadcrimedetector.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.video.Quality
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.example.roadcrimedetector.processing.FrameProcessor
import com.example.roadcrimedetector.location.LocationProvider
import com.example.roadcrimedetector.storage.IncidentRepository
import com.example.roadcrimedetector.utils.toBitmapRGBA
import java.util.concurrent.Executors

@Composable
fun CameraScreen(targetFps: Int) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val processor = remember { FrameProcessor(context) }
    val cameraController = remember { CameraController(context) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val recorder = remember { RollingVideoRecorder(context, executor) }
    val incidentRepository = remember { IncidentRepository(context) }
    val locationProvider = remember { LocationProvider(context) }

    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showIncidents by remember { mutableStateOf(false) }
    var selectedIncident by remember { mutableStateOf<com.example.roadcrimedetector.model.Incident?>(null) }
    var incidents by remember { mutableStateOf(incidentRepository.readAll().reversed()) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    var lastTime by remember { mutableStateOf(0L) }
    val frameInterval = 1000L / targetFps

    LaunchedEffect(Unit) {
        val preview = Preview.Builder().build()
        val analysis = cameraController.createAnalysis()
        analysisUseCase = analysis
        val videoCapture = VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
        )

        analysis.setAnalyzer(executor) { image ->

            val now = System.currentTimeMillis()
            if (now - lastTime >= frameInterval) {
                lastTime = now

                val bitmap = image.toBitmapRGBA()
                val processedFrame = processor.process(bitmap)
                processedFrame.violations.forEach { violation ->
                    val incident = incidentRepository.save(
                        violation,
                        locationProvider.lastKnownLocation(),
                        processedFrame.bitmap
                    )
                    recorder.captureIncident(incident.id) { videoFiles ->
                        incidentRepository.attachVideos(incident.id, videoFiles)
                        incidents = incidentRepository.readAll().reversed()
                    }
                }
                processedBitmap = processedFrame.bitmap
            }

            image.close()
        }

        cameraController.bind(lifecycleOwner, preview, analysis, videoCapture)
        recorder.start(videoCapture)
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.close()
            processor.close()
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedIncident != null) {
            IncidentDetailsScreen(
                incident = selectedIncident!!,
                onBack = {
                    selectedIncident = null
                    incidents = incidentRepository.readAll().reversed()
                },
                onDetailsChanged = { emailContent, roadName ->
                    incidentRepository.updateDetails(
                        selectedIncident!!.id,
                        emailContent,
                        roadName
                    )
                    selectedIncident = selectedIncident!!.copy(
                        emailContent = emailContent,
                        roadName = roadName
                    )
                }
            )
        } else if (showIncidents) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Button(onClick = {
                    showIncidents = false
                    incidents = incidentRepository.readAll().reversed()
                }) {
                    Text("Wróć do kamery")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(incidents, key = { it.id }) { incident ->
                        IncidentCard(incident) { selectedIncident = incident }
                    }
                }
            }
        } else {
            processedBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Processed camera feed",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Button(
                onClick = {
                    analysisUseCase?.clearAnalyzer()
                    incidents = incidentRepository.readAll().reversed()
                    showIncidents = true
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Zatrzymaj analizę i pokaż zdarzenia")
            }
        }
    }
}

@Composable
private fun IncidentCard(
    incident: com.example.roadcrimedetector.model.Incident,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            incident.snapshotFile?.let { path ->
                BitmapFactory.decodeFile(path)?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Klatka zdarzenia",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            }
            Text("Typ: ${incident.violation.violationType()}")
            Text(incident.violation.description)
        }
    }
}