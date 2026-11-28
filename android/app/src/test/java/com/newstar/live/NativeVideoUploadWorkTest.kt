package com.newstar.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeVideoUploadWorkTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return if (workerClassName == NativeVideoUploadWorker::class.java.name) {
                    RetryOnlyTestWorker(appContext, workerParameters)
                } else {
                    null
                }
            }
        }
        val configuration = Configuration.Builder()
            .setWorkerFactory(factory)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun enqueueUsesOneNetworkConstrainedWorkChainPerUser() {
        val userId = "upload-test-user"
        val uri = android.net.Uri.parse("content://com.newstar.test/video/1")

        NativeVideoUploadWork.enqueue(
            context, uri, userId, "First", "Description", "General",
            "public", "all", "video", "", null, "public"
        )
        NativeVideoUploadWork.enqueue(
            context, uri, userId, "Second", "Description", "General",
            "public", "all", "video", "", null, "public"
        )

        val infos = workManager.getWorkInfosForUniqueWork(
            NativeVideoUploadWork.uniqueName(userId)
        ).get(5, TimeUnit.SECONDS)

        assertEquals(1, infos.size)
        val info = infos.single()
        assertNotNull(info)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.tags.contains(NativeVideoUploadWork.uniqueName(userId)))
    }

    @Test
    fun cancellingUserUploadCancelsItsUniqueWorkChain() {
        val userId = "cancel-test-user"
        NativeVideoUploadWork.enqueue(
            context,
            android.net.Uri.parse("content://com.newstar.test/video/2"),
            userId,
            "Cancel test",
            "",
            "General",
            "public",
            "all",
            "video",
            "",
            null,
            "public"
        )

        NativeVideoUploadWork.cancel(context, userId)
        val infos = workManager.getWorkInfosForUniqueWork(
            NativeVideoUploadWork.uniqueName(userId)
        ).get(5, TimeUnit.SECONDS)

        assertTrue(infos.isNotEmpty())
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.ENQUEUED })
    }

    private class RetryOnlyTestWorker(
        context: Context,
        parameters: WorkerParameters
    ) : Worker(context, parameters) {
        override fun doWork(): Result = Result.retry()
    }
}
