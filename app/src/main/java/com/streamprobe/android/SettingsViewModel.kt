package com.streamprobe.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streamprobe.android.data.DebugSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: DebugSettingsRepository) : ViewModel() {
    val injectErrors: StateFlow<Boolean> =
        repo.injectErrorsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setInjectErrors(value: Boolean) = viewModelScope.launch { repo.setInjectErrors(value) }

    companion object {
        fun factory(app: StreamProbeApplication) = viewModelFactory {
            initializer { SettingsViewModel(app.debugSettings) }
        }
    }
}
