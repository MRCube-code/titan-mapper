package com.titanmapper.service.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.titanmapper.core.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Starts the service after boot only when the user opted in — never silently. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val value = settings.settings.first()
                if (value.startServiceOnBoot) TitanInputService.start(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
