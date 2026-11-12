package com.music.spotui.data.offline

import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

class InterruptibleHttpTest {
    @Test fun callbackResultIsReturnedAndResponseClosed() {
        val call = FakeCall()
        val result = AtomicReference<String?>(); val error = AtomicReference<Throwable?>()
        val reader = thread { try { result.set(InterruptibleHttp.read(call) { it.body!!.string() }) } catch (t: Throwable) { error.set(t) } }
        assertTrue(call.enqueued.await(3, TimeUnit.SECONDS)); call.respond(); reader.joinChecked()
        assertNull(error.get()); assertEquals("metadata", result.get()); assertTrue(call.bodyClosed.get())
        assertEquals(1, call.enqueues.get()); assertEquals(0, call.cancels.get())
    }

    @Test fun interruptedPendingReadCancelsExactlyOnceAndDoesNotRetry() {
        val call = FakeCall(); val error = AtomicReference<Throwable?>(); val interrupted = AtomicBoolean()
        val reader = thread { try { InterruptibleHttp.read(call) { "wrong" } } catch (t: Throwable) { error.set(t); interrupted.set(Thread.currentThread().isInterrupted) } }
        assertTrue(call.enqueued.await(3, TimeUnit.SECONDS)); reader.interrupt(); reader.joinChecked()
        assertTrue(error.get() is InterruptedException); assertTrue(interrupted.get())
        assertEquals(1, call.enqueues.get()); assertEquals(1, call.cancels.get())
    }

    @Test fun preInterruptedReadNeverEnqueuesARequest() {
        val call = FakeCall(); val error = AtomicReference<Throwable?>()
        val reader = thread { Thread.currentThread().interrupt(); try { InterruptibleHttp.read(call) { "wrong" } } catch (t: Throwable) { error.set(t) } }
        reader.joinChecked(); assertTrue(error.get() is InterruptedException); assertEquals(0, call.enqueues.get())
    }

    @Test fun interruptionDuringCallbackDecodeCancelsAndClosesResponse() {
        val call = FakeCall(); val entered = CountDownLatch(1); val error = AtomicReference<Throwable?>()
        val reader = thread {
            try { InterruptibleHttp.read(call) { entered.countDown(); call.cancelled.await(); throw IOException("cancelled body read") } }
            catch (t: Throwable) { error.set(t) }
        }
        assertTrue(call.enqueued.await(3, TimeUnit.SECONDS)); val callback = thread { call.respond() }
        assertTrue(entered.await(3, TimeUnit.SECONDS)); reader.interrupt()
        reader.joinChecked(); callback.joinChecked()
        assertTrue(error.get() is InterruptedException); assertEquals(1, call.cancels.get()); assertTrue(call.bodyClosed.get())
    }

    @Test fun backendFailureIsPropagatedWithoutStartingARetry() {
        val call = FakeCall(); val error = AtomicReference<Throwable?>()
        val reader = thread { try { InterruptibleHttp.read(call) { "wrong" } } catch (t: Throwable) { error.set(t) } }
        assertTrue(call.enqueued.await(3, TimeUnit.SECONDS)); call.fail(IOException("backend unavailable")); reader.joinChecked()
        assertEquals("backend unavailable", error.get()?.message); assertEquals(1, call.enqueues.get()); assertEquals(0, call.cancels.get())
    }

    private class FakeCall : Call {
        val enqueued = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val enqueues = AtomicInteger(); val cancels = AtomicInteger(); val bodyClosed = AtomicBoolean()
        private val delivered = AtomicBoolean(); private val cancelledFlag = AtomicBoolean()
        @Volatile private var callback: Callback? = null
        private val req = Request.Builder().url("https://metadata.invalid/example").build()
        private val tags = mutableMapOf<Class<*>, Any>()
        override fun request() = req
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; enqueues.incrementAndGet(); enqueued.countDown() }
        override fun execute(): Response = error("Fake transport only supports enqueue")
        override fun cancel() {
            cancels.incrementAndGet(); cancelledFlag.set(true); cancelled.countDown()
            if (delivered.compareAndSet(false, true)) callback?.onFailure(this, IOException("cancelled"))
        }
        override fun isExecuted() = enqueues.get() > 0
        override fun isCanceled() = cancelledFlag.get()
        override fun timeout() = Timeout()
        public override fun clone(): Call = FakeCall()
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = synchronized(tags) { type.cast(tags[type]) }
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = synchronized(tags) {
            type.cast(tags[type]) ?: computeIfAbsent().also { tags[type] = it }
        }
        fun fail(failure: IOException) { if (delivered.compareAndSet(false, true)) callback!!.onFailure(this, failure) }
        fun respond() {
            if (!delivered.compareAndSet(false, true)) return
            val body = object : ResponseBody() {
                private val source = object : ForwardingSource(Buffer().writeUtf8("metadata")) {
                    override fun close() { bodyClosed.set(true); super.close() }
                }.buffer()
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = 8L
                override fun source(): BufferedSource = source
            }
            callback!!.onResponse(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
        }
    }
    private fun thread(block: () -> Unit) = Thread(block).apply { isDaemon = true; start() }
    private fun Thread.joinChecked() { join(4_000); assertFalse("HTTP worker did not finish", isAlive) }
}
