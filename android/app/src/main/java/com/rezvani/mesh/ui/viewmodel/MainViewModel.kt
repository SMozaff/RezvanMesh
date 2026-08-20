package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the explicit first-run completion state, independent of identity creation. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _isOnboardingComplete = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false))
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    fun setOnboardingComplete(complete: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()
        _isOnboardingComplete.value = complete
    }

    companion object {
        private const val PREFS = "rezvan_settings"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
