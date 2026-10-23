package com.asawaaah.fil.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.asawaaah.fil.FilApp
import com.asawaaah.fil.ai.ModelCatalog
import com.asawaaah.fil.ai.SummaryProgress
import com.asawaaah.fil.data.settings.Settings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Periodic refresh: new articles, cleanup, then language detection and title translation. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FilApp).container
        c.feedRepository.refreshAll()
        c.feedRepository.purgeOld()
        c.translationService.processPending()
        c.workScheduler.enqueuePrepareSummaries()
        return Result.success()
    }
}

/** Summarizes recent unread articles ahead of time, only while the phone is charging. */
class PrepareSummariesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FilApp).container
        val s = c.settings.current()
        if (!s.prepareSummaries || !c.modelManager.isInstalled(ModelCatalog.byId(s.selectedModelId))) {
            return Result.success()
        }
        val started = System.currentTimeMillis()
        val since = started - 2 * 24 * 60 * 60 * 1000L
        val articles = c.db.articleDao().recentWithoutSummary(since, limit = 12)
        for (article in articles) {
            // Background work is capped at 10 minutes: stop early and let the next run continue.
            if (isStopped || System.currentTimeMillis() - started > 7 * 60_000L) break
            val result = c.summaryService.summarize(article.id).first { it !is SummaryProgress.Working }
            if (result is SummaryProgress.Failed && result.message.contains("modèle")) break
        }
        c.llm.releaseSoon()
        return Result.success()
    }
}

class WorkScheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    fun apply(settings: Settings) {
        if (settings.refreshHours <= 0) {
            wm.cancelUniqueWork(REFRESH)
        } else {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(settings.refreshHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        if (settings.prepareSummaries) {
            val request = PeriodicWorkRequestBuilder<PrepareSummariesWorker>(3, TimeUnit.HOURS)
                .setConstraints(chargingConstraints())
                .build()
            wm.enqueueUniquePeriodicWork(PREPARE, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            wm.cancelUniqueWork(PREPARE)
            wm.cancelUniqueWork(PREPARE_NOW)
        }
    }

    /** Runs a summary pass soon, the next time the phone is charging. */
    fun enqueuePrepareSummaries() {
        val request = OneTimeWorkRequestBuilder<PrepareSummariesWorker>()
            .setConstraints(chargingConstraints())
            .build()
        wm.enqueueUniqueWork(PREPARE_NOW, ExistingWorkPolicy.KEEP, request)
    }

    private fun chargingConstraints() = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        private const val REFRESH = "refresh"
        private const val PREPARE = "prepare-summaries"
        private const val PREPARE_NOW = "prepare-summaries-now"
    }
}
