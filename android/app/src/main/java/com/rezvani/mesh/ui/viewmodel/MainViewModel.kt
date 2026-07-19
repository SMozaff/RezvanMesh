package com.rezvani.mesh.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.rezvani.mesh.backup.IdentityBackupHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // If secure storage is unavailable, treat onboarding as incomplete rather
    // than crash the ViewModel constructor; MainActivity's blocking error
    // screen is the actual user-facing signal for this failure.
    private val _isOnboardingComplete = MutableStateFlow(
        try {
            IdentityBackupHelper.hasIdentity(application)
        } catch (e: com.rezvani.mesh.backup.IdentityStorageException) {
            false
        }
    )
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    fun setOnboardingComplete(complete: Boolean) {
        _isOnboardingComplete.value = complete
    }
}
