package com.asawaaah.fil.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.asawaaah.fil.FilApp
import com.asawaaah.fil.data.net.ErrorMessages
import com.asawaaah.fil.data.net.HttpStatusException
import com.asawaaah.fil.data.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.FileOutputStream

/** Downloads a model file over Wi-Fi, resuming a partial download when possible. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FilApp).container
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL))
        val manager = container.modelManager
        if (manager.isInstalled(model)) return Result.success()

        runCatching { setForeground(foregroundInfo(model, 0, model.approxBytes)) }

        val part = manager.partFile(model)
        return try {
            withContext(Dispatchers.IO) {
                var downloaded = if (part.exists()) part.length() else 0L
                val request = Request.Builder()
                    .url(model.url)
                    .header("User-Agent", "Fil/0.1 (Android)")
                    .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
                    .build()
                val total: Long
                container.http.client.newCall(request).await().use { response ->
                    if (response.code == 416) {
                        // Range past the end: the partial file is already complete.
                        total = downloaded
                        return@use
                    }
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    val append = response.code == 206
                    if (!append) downloaded = 0
                    val length = response.body.contentLength()
                    total = if (length > 0) downloaded + length else model.approxBytes
                    var lastReport = 0L
                    FileOutputStream(part, append).use { out ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (isStopped) throw CancellationException("Arrêté")
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 700) {
                                    lastReport = now
                                    setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to total))
                                    runCatching { setForeground(foregroundInfo(model, downloaded, total)) }
                                }
                            }
                        }
                    }
                }
                if (part.length() < total) error("Téléchargement incomplet")
                val target = manager.file(model)
                target.delete()
                check(part.renameTo(target)) { "Impossible d'enregistrer le modèle" }
            }
            manager.refreshInstalled()
            container.workScheduler.enqueuePrepareSummaries()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ModelDownload", "Download interrupted at ${part.length()} bytes", e)
            // Network cuts are common on a 2.5 GB download: keep resuming while the file grows.
            if (runAttemptCount < 20 && e !is HttpStatusException) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to ErrorMessages.describe(e)))
            }
        }
    }

    private fun foregroundInfo(model: LlmModel, downloaded: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        }
        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Téléchargement de ${model.name}")
            .setContentText("$percent % · ${downloaded / 1_000_000} Mo sur ${total / 1_000_000} Mo")
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4201

        fun request(model: LlmModel, allowMobileData: Boolean = false): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL to model.id))
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
    }
}
