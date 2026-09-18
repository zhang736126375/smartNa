package com.bingo.coresdk.route

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bingo.coresdk.vi.VMsgConstant
import com.bingo.coresdk.vi.VMsgDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RoadConditionControllerTest {

    @Test
    fun dispatchFromBackground_arrivesOnMainThread() {
        val latch = CountDownLatch(1)
        val taskId = AtomicInteger(-1)
        val callbackThread = AtomicReference("")
        val listener = IRoadConditionListener { _, id, _, _, _ ->
            taskId.set(id)
            callbackThread.set(Thread.currentThread().name)
            latch.countDown()
        }
        RoadConditionController.addListener(listener)
        try {
            Thread {
                val bundle = Bundle().apply {
                    putInt(JniConstant.ROAD_CONDITION_TASK_ID, 42)
                    putInt(JniConstant.ROAD_CONDITION_CITY_ID, 131)
                    putString(JniConstant.ROAD_CONDITION_ROAD_NAME, "长安街")
                    putString(JniConstant.ROAD_CONDITION_NATIVE_THREAD, "rc-worker")
                }
                VMsgDispatcher.dispatchMessage(
                    VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE,
                    0,
                    0,
                    bundle
                )
            }.start()
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(42, taskId.get())
            assertTrue(
                "callback should be on main, was ${callbackThread.get()}",
                callbackThread.get() == "main" || callbackThread.get().contains("main")
            )
        } finally {
            RoadConditionController.removeListener(listener)
        }
    }
}
