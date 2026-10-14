package com.example.roadcrimedetector.camera

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.VideoView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.roadcrimedetector.model.Incident
import com.example.roadcrimedetector.location.NominatimGeocoder
import com.example.roadcrimedetector.storage.PersonalDataRepository
import com.example.roadcrimedetector.utils.getEmailForVoivodeship
import com.example.roadcrimedetector.utils.mergeVideosIntoSingleFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun IncidentDetailsScreen(
    incident: Incident,
    onBack: () -> Unit
) {
    var emailContent by rememberSaveable(incident.id) { mutableStateOf(incident.emailContent) }
    var voivodeship by remember(incident.id) { mutableStateOf("") }
    var isPreparingEmail by remember { mutableStateOf(false) }
    var isGeneratingEmail by remember(incident.id) { mutableStateOf(true) }
    val context = LocalContext.current
    val personalDataRepository = remember(context) { PersonalDataRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(incident.id) {
        isGeneratingEmail = true
        try {
            val address = incident.location?.let {
                NominatimGeocoder().findAddress(it.latitude, it.longitude)
            }
            voivodeship = address?.state.orEmpty()
            emailContent = incident.violation.generateEmail(
                incident.location,
                incident.snapshotFile,
                personalDataRepository.load()
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            emailContent = ""
            Log.e("IncidentDetailsScreen", "Nie udało się wygenerować treści zgłoszenia", exception)
            Toast.makeText(
                context,
                "Nie udało się przygotować treści emaila",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            isGeneratingEmail = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF424242))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF555555),
                contentColor = Color.White
            )
        ) {
            Text("← Wróć do wykroczeń", color = Color.White)
        }
        Text(
            "Podgląd zdarzenia",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
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
        IncidentVideoPlayer(incident.videoFiles)

        Text(
            "Podstawowe informacje",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text("Typ: ${incident.violation.violationType()}", color = Color.White)
        Text("Opis: ${incident.violation.description}", color = Color.White)
        Text("Czas: ${incident.violation.eventTimestampPolish}", color = Color.White)
        incident.location?.let { location ->
            Text(
                "Lokalizacja: ${location.latitude}, ${location.longitude}",
                color = Color.White
            )
        }
        Button(
            enabled = !isPreparingEmail && !isGeneratingEmail && emailContent.isNotBlank(),
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
                            putExtra(
                                Intent.EXTRA_SUBJECT,
                                "Zgłoszenie zdarzenia drogowego: ${incident.violation.description}"
                            )
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
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF7041C5),
                contentColor = Color.White,
                disabledBackgroundColor = Color(0xFF555555),
                disabledContentColor = Color.White
            )
        ) {
            Text(
                if (isPreparingEmail || isGeneratingEmail) "Przygotowywanie..." else "Wyślij email",
                color = Color.White
            )
        }
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
        Text("Brak zapisanego klipu wideo", color = Color.White)
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