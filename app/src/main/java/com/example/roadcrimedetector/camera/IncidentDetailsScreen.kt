package com.example.roadcrimedetector.camera

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.VideoView
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.roadcrimedetector.model.Incident
import com.example.roadcrimedetector.location.NominatimGeocoder
import com.example.roadcrimedetector.utils.getEmailForVoivodeship
import com.example.roadcrimedetector.utils.mergeVideosIntoSingleFile
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun IncidentDetailsScreen(
    incident: Incident,
    onBack: () -> Unit,
    onDetailsChanged: (emailContent: String, roadName: String) -> Unit
) {
    var emailContent by rememberSaveable(incident.id) { mutableStateOf(incident.emailContent) }
    var roadName by rememberSaveable(incident.id) { mutableStateOf(incident.roadName) }
    var voivodeship by remember(incident.id) { mutableStateOf("") }
    var isPreparingEmail by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(incident.id) {
        val address = incident.location?.let {
            NominatimGeocoder().findAddress(it.latitude, it.longitude)
        }
        voivodeship = address?.state.orEmpty()
        emailContent = incident.violation.generateEmail(incident.location)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onBack) {
            Text("Wróć do wykroczeń")
        }
        Button(
            enabled = !isPreparingEmail,
            onClick = {
                coroutineScope.launch {
                    isPreparingEmail = true
                    val mergedVideo = mergeVideosIntoSingleFile(
                        videoFiles = incident.videoFiles.map(::File),
                        outputFile = File(context.cacheDir, "incident_${incident.id}.mp4")
                    )
                    if (mergedVideo == null) {
                        Toast.makeText(context, "Brak nagrania wideo", Toast.LENGTH_SHORT).show()
                    } else {
                        val shareUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            mergedVideo
                        )
                        val emailIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(getEmailForVoivodeship(voivodeship)))
                            putExtra(Intent.EXTRA_SUBJECT, "Testowe zgłoszenie zdarzenia drogowego")
                            putExtra(Intent.EXTRA_TEXT, emailContent)
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(Intent.createChooser(emailIntent, "Wyślij email"))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "Brak aplikacji do wysyłania emaili", Toast.LENGTH_SHORT).show()
                        }
                    }
                    isPreparingEmail = false
                }
            }
        ) {
            Text(if (isPreparingEmail) "Przygotowywanie..." else "Akceptuj i wyślij email")
        }
        Text("Szczegóły wykroczenia")
        Text("Typ: ${incident.violation.violationType()}")
        Text(incident.violation.description)
        Text("Etykieta: ${incident.violation.detection.label}")
        Text("Pewność: ${incident.violation.detection.confidence}")
        Text("ID klasy: ${incident.violation.detection.classId}")
        Text("Czas nagrania: ${incident.recordedAtMillis}")
        Text("Czas wykrycia: ${incident.violation.eventTimestampPolish}")
        Text(
            "Pozycja pojazdu: " +
                "(${incident.violation.position.x1}, ${incident.violation.position.y1}) - " +
                "(${incident.violation.position.x2}, ${incident.violation.position.y2})"
        )
        incident.location?.let { location ->
            Text("Lokalizacja: ${location.latitude}, ${location.longitude}")
            Text("Dokładność: ${location.accuracyMeters ?: "brak"} m")
            Text("Wysokość: ${location.altitudeMeters ?: "brak"} m")
            Text("Źródło lokalizacji: ${location.provider ?: "brak"}")
        }
        incident.snapshotFile?.let { path ->
            BitmapFactory.decodeFile(path)?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Klatka zdarzenia",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }
        }
        OutlinedTextField(
            value = emailContent,
            onValueChange = {
                emailContent = it
                onDetailsChanged(it, roadName)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Treść emaila") }
        )
        OutlinedTextField(
            value = roadName,
            onValueChange = {
                roadName = it
                onDetailsChanged(emailContent, it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nazwa drogi") }
        )
        IncidentVideoPlayer(incident.videoFiles)
    }
}

@Composable
private fun IncidentVideoPlayer(videoFiles: List<String>) {
    val context = LocalContext.current
    val playableFiles = remember(videoFiles) {
        videoFiles.map(::File).filter(File::exists)
    }
    val videoView = remember { VideoView(context) }
    var currentFileIndex by remember(playableFiles) { mutableStateOf(0) }
    val currentFileIndexState by rememberUpdatedState(currentFileIndex)

    if (playableFiles.isEmpty()) {
        Text("Brak zapisanego klipu wideo")
        return
    }

    LaunchedEffect(playableFiles, currentFileIndex) {
        videoView.setVideoPath(playableFiles[currentFileIndex].absolutePath)
        videoView.start()
    }

    DisposableEffect(videoView, playableFiles) {
        val listener = android.media.MediaPlayer.OnCompletionListener {
            if (currentFileIndexState < playableFiles.lastIndex) {
                currentFileIndex++
            }
        }
        videoView.setOnCompletionListener(listener)
        onDispose {
            videoView.stopPlayback()
            videoView.setOnCompletionListener(null)
        }
    }

    AndroidView(
        factory = { videoView },
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    )
}