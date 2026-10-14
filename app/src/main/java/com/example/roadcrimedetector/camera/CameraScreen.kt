package com.example.roadcrimedetector.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.video.Quality
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roadcrimedetector.R
import com.example.roadcrimedetector.processing.FrameProcessor
import com.example.roadcrimedetector.location.LocationProvider
import com.example.roadcrimedetector.storage.IncidentRepository
import com.example.roadcrimedetector.storage.PersonalDataRepository
import com.example.roadcrimedetector.utils.mergeVideosIntoSingleFile
import com.example.roadcrimedetector.utils.toBitmapRGBA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.io.File
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
    val personalDataRepository = remember { PersonalDataRepository(context) }
    val locationProvider = remember { LocationProvider(context) }

    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showIncidents by remember { mutableStateOf(false) }
    var showClearIncidentsConfirmation by remember { mutableStateOf(false) }
    var showPersonalData by remember { mutableStateOf(false) }
    var isPreviewHidden by remember { mutableStateOf(false) }
    var isExportingVideo by remember { mutableStateOf(false) }
    var personalData by remember { mutableStateOf(personalDataRepository.load()) }
    var selectedIncident by remember { mutableStateOf<com.example.roadcrimedetector.model.Incident?>(null) }
    var incidents by remember { mutableStateOf(incidentRepository.readAll().reversed()) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    var analysisAnalyzer by remember { mutableStateOf<ImageAnalysis.Analyzer?>(null) }
    var lastTime by remember { mutableStateOf(0L) }
    val frameInterval = 1000L / targetFps
    val coroutineScope = rememberCoroutineScope()
    val exportVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destination ->
        if (destination == null) {
            isExportingVideo = false
        } else {
            coroutineScope.launch {
                val mergedFile = File(context.cacheDir, "last_5_minutes_${System.currentTimeMillis()}.mp4")
                try {
                    val segments = recorder.snapshotStoredSegments()
                    val mergedVideo = mergeVideosIntoSingleFile(segments, mergedFile)
                    if (mergedVideo == null) {
                        Toast.makeText(context, "Brak zapisanego wideo", Toast.LENGTH_SHORT).show()
                    } else {
                        withContext(Dispatchers.IO) {
                            val output = context.contentResolver.openOutputStream(destination)
                                ?: error("Nie można otworzyć pliku docelowego")
                            output.use { stream ->
                                mergedVideo.inputStream().use { input -> input.copyTo(stream) }
                            }
                        }
                        Toast.makeText(context, "Wideo zapisane", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(context, "Nie udało się zapisać wideo", Toast.LENGTH_SHORT).show()
                } finally {
                    mergedFile.delete()
                    isExportingVideo = false
                }
            }
        }
    }

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

        val analyzer = ImageAnalysis.Analyzer { image ->
            val now = System.currentTimeMillis()
            if (now - lastTime >= frameInterval) {
                lastTime = now

                val rotationDegrees = image.imageInfo.rotationDegrees
                val bitmap = image.toBitmapRGBA(rotationDegrees)
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
        analysisAnalyzer = analyzer
        analysis.setAnalyzer(executor, analyzer)

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
        if (showPersonalData) {
            PersonalDataScreen(
                initialData = personalData,
                onBack = { showPersonalData = false },
                onSave = { updatedData ->
                    personalDataRepository.save(updatedData)
                    personalData = updatedData
                    showPersonalData = false
                }
            )
        } else if (selectedIncident != null) {
            IncidentDetailsScreen(
                incident = selectedIncident!!,
                onBack = {
                    selectedIncident = null
                    incidents = incidentRepository.readAll().reversed()
                }
            )
        } else if (showIncidents) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = {
                        showIncidents = false
                        incidents = incidentRepository.readAll().reversed()
                        analysisAnalyzer?.let { analyzer ->
                            analysisUseCase?.setAnalyzer(executor, analyzer)
                        }
                    }) {
                        Text("Wróć do kamery")
                    }
                    IconButton(
                        onClick = {
                            showClearIncidentsConfirmation = true
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Usuń wszystkie incydenty"
                        }
                    ) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_delete),
                            contentDescription = null,
                            tint = Color.Red
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(incidents, key = { it.id }) { incident ->
                        IncidentCard(
                            incident = incident,
                            onClick = { selectedIncident = incident },
                            onDelete = {
                                incidentRepository.delete(incident.id)
                                incidents = incidentRepository.readAll().reversed()
                            }
                        )
                    }
                }
            }
        } else {
            if (isPreviewHidden) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            } else {
                processedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Processed camera feed",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundActionButton(
                        iconResId = R.drawable.ic_account,
                        description = if (personalData.isComplete) {
                            "Edytuj dane osobowe"
                        } else {
                            "Uzupełnij dane osobowe"
                        },
                        onClick = { showPersonalData = true }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    RoundActionButton(
                        iconResId = if (isPreviewHidden) {
                            R.drawable.ic_video_camera
                        } else {
                            R.drawable.ic_video_camera_slash
                        },
                        description = if (isPreviewHidden) "Włącz podgląd" else "Wyłącz podgląd",
                        onClick = { isPreviewHidden = !isPreviewHidden }
                    )
                    RoundActionButton(
                        iconResId = R.drawable.ic_exclamation_circle,
                        description = "Otwórz wykryte zdarzenia",
                        onClick = {
                            analysisUseCase?.clearAnalyzer()
                            incidents = incidentRepository.readAll().reversed()
                            showIncidents = true
                        }
                    )
                    RoundActionButton(
                        iconResId = R.drawable.ic_download,
                        description = if (isExportingVideo) {
                            "Przygotowywanie wideo"
                        } else {
                            "Pobierz ostatnie 5 minut nagrania"
                        },
                        enabled = !isExportingVideo,
                        onClick = {
                            isExportingVideo = true
                            exportVideoLauncher.launch("road-crime-last-5-minutes.mp4")
                        }
                    )
                }
            }
        }
        if (showClearIncidentsConfirmation) {
            AlertDialog(
                onDismissRequest = { showClearIncidentsConfirmation = false },
                title = { Text("Usunąć wszystkie incydenty?") },
                text = { Text("Zostaną usunięte także zapisane klatki i nagrania.") },
                confirmButton = {
                    Button(onClick = {
                        incidentRepository.clear()
                        incidents = emptyList()
                        showClearIncidentsConfirmation = false
                    }) {
                        Text("Usuń wszystkie")
                    }
                },
                dismissButton = {
                    Button(onClick = { showClearIncidentsConfirmation = false }) {
                        Text("Anuluj")
                    }
                }
            )
        }
    }
}

@Composable
private fun RoundActionButton(
    iconResId: Int,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (enabled) ActionPurple else ActionPurple.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconResId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(28.dp)
        )
    }
}

private val ActionPurple = Color(0xFF7041C5)

@Composable
private fun IncidentCard(
    incident: com.example.roadcrimedetector.model.Incident,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = 4.dp
    ) {
        Box {
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
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .semantics { contentDescription = "Usuń incydent" }
            ) {
                Text("×", color = Color.Red, fontSize = 24.sp)
            }
        }
    }
}