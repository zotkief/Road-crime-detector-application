package com.example.roadcrimedetector.storage

import android.content.Context
import com.example.roadcrimedetector.model.PersonalData

class PersonalDataRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): PersonalData = PersonalData(
        firstName = preferences.getString(KEY_FIRST_NAME, "").orEmpty(),
        lastName = preferences.getString(KEY_LAST_NAME, "").orEmpty(),
        homeAddress = preferences.getString(KEY_HOME_ADDRESS, "").orEmpty()
    )

    fun save(data: PersonalData) {
        preferences.edit()
            .putString(KEY_FIRST_NAME, data.firstName)
            .putString(KEY_LAST_NAME, data.lastName)
            .putString(KEY_HOME_ADDRESS, data.homeAddress)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "personal_data"
        const val KEY_FIRST_NAME = "first_name"
        const val KEY_LAST_NAME = "last_name"
        const val KEY_HOME_ADDRESS = "home_address"
    }
}