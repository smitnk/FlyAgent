package com.smitnk.flyagent

import android.content.Context
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** A task the agent runs on its own every day at a set time, e.g. "8:00 — news on animation and AI". */
data class Schedule(val id: String, val prompt: String, val hour: Int, val minute: Int, val dotId: String, val enabled: Boolean = true) {
    fun json() = JSONObject().put("id", id).put("prompt", prompt).put("hour", hour).put("minute", minute).put("dot", dotId).put("enabled", enabled)
    companion object { fun from(o: JSONObject) = Schedule(o.getString("id"), o.getString("prompt"), o.getInt("hour"), o.getInt("minute"), o.optString("dot", "flyagent"), o.optBoolean("enabled", true)) }
}

object Schedules {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("schedules", Context.MODE_PRIVATE)
    fun list(ctx: Context) = JSONArray(prefs(ctx).getString("list", "[]")).let { a -> (0 until a.length()).map { Schedule.from(a.getJSONObject(it)) } }
    fun save(ctx: Context, all: List<Schedule>) {
        prefs(ctx).edit().putString("list", JSONArray(all.map { it.json() }).toString()).apply()
        val wm = WorkManager.getInstance(ctx)
        wm.cancelAllWorkByTag("schedule")
        all.filter { it.enabled }.forEach { s ->
            wm.enqueueUniquePeriodicWork("sched-${s.id}", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ScheduleWorker>(24, TimeUnit.HOURS).addTag("schedule")
                    .setInitialDelay(delayMs(s.hour, s.minute, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf("id" to s.id)).build())
        }
    }
    fun runNow(ctx: Context, s: Schedule) = WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<ScheduleWorker>().setInputData(workDataOf("id" to s.id)).build())

    /** Milliseconds from [now] until the next hh:mm. */
    fun delayMs(hour: Int, minute: Int, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - now
    }
}

class ScheduleWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val s = Schedules.list(applicationContext).firstOrNull { it.id == inputData.getString("id") } ?: return Result.success()
        AgentHub.init(applicationContext)
        val answer = runCatching { AgentHub.runScheduled(s.prompt, s.dotId) }.getOrElse { "Failed: ${it.message}" }
        Notify.done(applicationContext, "⏰ ${s.prompt.take(40)}", answer)
        return Result.success()
    }
}
