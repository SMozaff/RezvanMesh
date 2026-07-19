package com.rezvani.mesh.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.ui.screens.OnboardingStep
import com.rezvani.mesh.ui.screens.OnboardingUiState
import java.security.SecureRandom

class OnboardingViewModel : ViewModel() {

    private val _uiState = mutableStateOf(OnboardingUiState())
    val uiState: State<OnboardingUiState> = _uiState

    fun enterMesh(context: Context) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        try {
            val existingSeed = IdentityBackupHelper.loadSeed(context)
            if (existingSeed != null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    step = OnboardingStep.DONE
                )
                return
            }

            // Identity always comes from a securely generated random seed --
            // never derived from a hardware identifier like the MAC address,
            // which is public/guessable and would let an attacker recompute
            // this device's private keys (see security audit finding #2).
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)

            IdentityBackupHelper.saveSeed(context, seed)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                step = OnboardingStep.DONE
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = e.message ?: "Failed to create identity"
            )
        }
    }
}
