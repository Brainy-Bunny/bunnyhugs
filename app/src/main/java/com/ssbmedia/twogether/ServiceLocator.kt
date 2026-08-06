package com.ssbmedia.twogether

import android.content.Context
import com.ssbmedia.twogether.data.datastore.BadgeUnlocksStore
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.data.datastore.ProximityStateStore
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.ListCategoryRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.data.repo.TimeCapsuleRepository
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple hand-rolled service locator: no DI framework needed for an app this size. */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Application-process-scoped coroutine scope that outlives any single screen's viewModelScope.
     * Used for teardown work that MUST run to completion even if the screen that kicked it off gets
     * disposed mid-flight as a direct side effect of that same work - e.g. SettingsScreen.unpair()
     * writes to pairingStore, which flips MainActivity's nav state and disposes SettingsScreen (and
     * cancels its viewModelScope) practically immediately; the rest of unpair()'s teardown (stopping
     * the foreground service, emitting the unpaired event) must not be cancelled by that. */
    /** BUG fix: an independent testing round found several distinct crash sites (setPin, clearPin,
     * unpair, restoreBackupDurable's DataStore calls, milestone-alarm scheduling on app start) that all
     * traced back to the same root cause - this scope had no CoroutineExceptionHandler, so ANY uncaught
     * exception on it (a genuine DataStore IOException, a malformed-data crash, anything) killed the
     * whole app process instead of just failing that one operation. Each of those call sites is also
     * being hardened individually where it matters for user-visible feedback (a failed PIN save should
     * show an error, not just "not crash") - this is the last-resort net underneath all of them, so a
     * FUTURE uncaught exception here degrades to a log line instead of a fatal crash by default. */
    private val applicationScopeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("ServiceLocator", "Uncaught exception on applicationScope", throwable)
    }
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + applicationScopeExceptionHandler)
    }

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val sessionRepository: SessionRepository by lazy { SessionRepository(database.sessionDao()) }
    val dateIdeaRepository: DateIdeaRepository by lazy { DateIdeaRepository(database.dateIdeaDao()) }
    val timeCapsuleRepository: TimeCapsuleRepository by lazy { TimeCapsuleRepository(database.timeCapsuleDao()) }
    val momentRepository: MomentRepository by lazy { MomentRepository(database.momentDao(), appContext) }
    val momentNoteRepository: MomentNoteRepository by lazy { MomentNoteRepository(database.momentNoteDao()) }
    val milestoneRepository: MilestoneRepository by lazy { MilestoneRepository(database.milestoneDao()) }
    val listCategoryRepository: ListCategoryRepository by lazy { ListCategoryRepository(database.listCategoryDao(), dateIdeaRepository, database) }

    val pairingStore: PairingStore by lazy { PairingStore(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val proximityStateStore: ProximityStateStore by lazy { ProximityStateStore(appContext) }
    val badgeUnlocksStore: BadgeUnlocksStore by lazy { BadgeUnlocksStore(appContext) }
}
