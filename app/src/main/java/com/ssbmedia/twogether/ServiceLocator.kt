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
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

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
