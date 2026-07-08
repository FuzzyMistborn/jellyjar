package com.fuzzymistborn.jellyjar.data.repository

import com.fuzzymistborn.jellyjar.model.AppSettings
import kotlinx.coroutines.flow.first

// Convenience extension to get a one-shot snapshot of current settings
suspend fun SettingsRepository.currentSnapshot(): AppSettings = settings.first()
