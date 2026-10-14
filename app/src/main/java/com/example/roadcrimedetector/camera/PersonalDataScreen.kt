package com.example.roadcrimedetector.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.roadcrimedetector.model.PersonalData

@Composable
fun PersonalDataScreen(
    initialData: PersonalData,
    onBack: () -> Unit,
    onSave: (PersonalData) -> Unit
) {
    var firstName by remember { mutableStateOf(initialData.firstName) }
    var lastName by remember { mutableStateOf(initialData.lastName) }
    var homeAddress by remember { mutableStateOf(initialData.homeAddress) }
    val textFieldColors = TextFieldDefaults.outlinedTextFieldColors(
        textColor = Color.White,
        backgroundColor = Color.White.copy(alpha = 0.12f),
        cursorColor = Color.White,
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dane osobowe")
        OutlinedTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("Imię") },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors
        )
        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Nazwisko") },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors
        )
        OutlinedTextField(
            value = homeAddress,
            onValueChange = { homeAddress = it },
            label = { Text("Adres zamieszkania") },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) {
                Text("Anuluj")
            }
            Button(onClick = {
                onSave(PersonalData(firstName, lastName, homeAddress))
            }) {
                Text("Zapisz")
            }
        }
    }
}