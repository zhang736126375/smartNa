package com.bingo.coresdk.vi

import android.os.Looper
import android.os.Message
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VMsgDispatcherTest {

    @Test
    fun dispatchMessage_deliversToObservingHandler() {
        val latch = CountDownLatch(1)
        val receivedWhat = AtomicInteger(-1)
        val receivedArg1 = AtomicInteger(-1)
        val handler = object : MsgHandler(Looper.getMainLooper()) {
            override fun careAbout() {
                observe(VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE)
            }

            override fun handleMessage(msg: Message) {
                receivedWhat.set(msg.what)
                receivedArg1.set(msg.arg1)
                latch.countDown()
            }
        }
        VMsgDispatcher.registerMsgHandler(handler)
        try {
            VMsgDispatcher.dispatchMessage(
                VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE,
                3,
                0,
                "payload"
            )
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE, receivedWhat.get())
            assertEquals(3, receivedArg1.get())
        } finally {
            VMsgDispatcher.unregisterMsgHandler(handler)
        }
    }

    @Test
    fun dispatchMessage_ignoresHandlerThatDidNotObserve() {
        val latch = CountDownLatch(1)
        val handler = object : MsgHandler(Looper.getMainLooper()) {
            override fun careAbout() {
                observe(99999)
            }

            override fun handleMessage(msg: Message) {
                if (msg.what == VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE) {
                    latch.countDown()
                }
            }
        }
        VMsgDispatcher.registerMsgHandler(handler)
        try {
            VMsgDispatcher.dispatchMessage(
                VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE,
                0,
                0,
                null
            )
            assertFalse(latch.await(400, TimeUnit.MILLISECONDS))
        } finally {
            VMsgDispatcher.unregisterMsgHandler(handler)
        }
    }
}
