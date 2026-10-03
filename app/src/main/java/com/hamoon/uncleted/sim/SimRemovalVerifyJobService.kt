package com.hamoon.uncleted.sim

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * Delayed-verification worker for physical-SIM removal. Scheduled with
 * [SimMonitor.ABSENT_VERIFY_DELAY_MS] of latency so a transient ABSENT from
 * a modem reset, airplane-mode flip, or boot-time radio restart cannot by
 * itself trigger a factory reset. Must be `directBootAware` because
 * [android.intent.action.LOCKED_BOOT_COMPLETED] runs before user unlock.
 */
class SimRemovalVerifyJobService : JobService() {

    companion object {
        private const val TAG = "SimRemovalVerifyJob"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "SIM removal verification job started.")
        SimMonitor.verifyPendingRemoval(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
