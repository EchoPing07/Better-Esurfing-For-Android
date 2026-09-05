package dev.echoping.betteresurfing.keep

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.echoping.betteresurfing.engine.Repo
import dev.echoping.betteresurfing.service.AuthService
import dev.echoping.betteresurfing.store.Prefs
import java.util.concurrent.TimeUnit

/**
 * WorkManager 兜底（FR-4.5）：服务被杀后周期拉起。
 */
class KeepAliveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        if (!Prefs.autoBoot || !Prefs.lastRunning) return Result.success()
        if (AuthService.isRunning) return Result.success()
        // 服务不在且最近确实在跑 → 拉起（系统会按 ROM 后台策略尽力执行）
        AuthService.start(applicationContext)
        Repo.onLog(1, "KeepAliveWorker 拉起服务")
        return Result.success()
    }

    companion object {
        private const val NAME = "be_keepalive"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
