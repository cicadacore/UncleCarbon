package com.hamoon.uncleted.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hamoon.uncleted.services.PanicActionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TripwireWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val tag = "TripwireWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.e(tag, "Tripwire timer expired! Device has been offline too long. Initiating TRIPWIRE_WIPE.")
        PanicActionService.trigger(applicationContext, "TRIPWIRE_WIPE", PanicActionService.Severity.CRITICAL)
        Result.success()
    }
}
