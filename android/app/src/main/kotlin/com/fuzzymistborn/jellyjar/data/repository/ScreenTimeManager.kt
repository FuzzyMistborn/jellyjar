package com.fuzzymistborn.jellyjar.data.repository

import com.fuzzymistborn.jellyjar.model.ScreenTimeUsage
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class TimesUpReason { EPISODE_STREAK, DAILY_LIMIT }

// Kid Mode screen-time limits. Everything here is a no-op unless Kid Mode is on, so a parent can
// set limits once and have them apply only while the tablet is in kid configuration.
//
// Uses the device clock's calendar day, so a kid who changes the date can reset it. Accepted for a
// home tablet; a PIN on Admin is what protects the settings themselves.
@Singleton
class ScreenTimeManager @Inject constructor(
    private val settings: SettingsRepository,
) {
    // Episodes played back-to-back via auto-play/Up Next. In memory on purpose: a fresh app start
    // (or any manually started title) begins a new streak.
    private var streakCount = 0
    private var streakBonus = 0

    private fun today(): String = LocalDate.now().toString()

    fun onPlayerStart(autoAdvanced: Boolean) {
        if (autoAdvanced) {
            streakCount++
        } else {
            streakCount = 1
            streakBonus = 0
        }
    }

    suspend fun usageToday(): ScreenTimeUsage = settings.screenTimeUsage.first().forDate(today())

    // Budget left today, or null when there is no daily limit in effect.
    suspend fun remainingMs(): Long? {
        val s = settings.currentSnapshot()
        if (!s.kidModeEnabled || s.dailyLimitMinutes <= 0) return null
        val usage = usageToday()
        if (usage.unlimited) return null
        return s.dailyLimitMinutes * 60_000L + usage.bonusMs - usage.usedMs
    }

    suspend fun streakReached(): Boolean {
        val s = settings.currentSnapshot()
        if (!s.kidModeEnabled || s.episodeStreakLimit <= 0) return false
        if (usageToday().unlimited) return false
        return streakCount >= s.episodeStreakLimit + streakBonus
    }

    suspend fun dailyLimitReached(): Boolean = (remainingMs() ?: Long.MAX_VALUE) <= 0L

    suspend fun addWatched(ms: Long) {
        if (ms <= 0 || !settings.currentSnapshot().kidModeEnabled) return
        settings.addScreenTime(today(), ms)
    }

    // ── Grown-up overrides (PIN-verified by the caller) ──────────────────────
    suspend fun grantMinutes(minutes: Int) = settings.grantScreenTimeBonus(today(), minutes * 60_000L)

    fun grantEpisode() {
        streakBonus++
    }

    suspend fun grantUnlimitedToday() = settings.grantUnlimitedToday(today())

    suspend fun resetToday() {
        streakCount = 0
        streakBonus = 0
        settings.resetScreenTime(today())
    }
}
