package com.music.spotui.data.offline

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Blocks the caller (a download), not an uncancellable Call.execute or a polling thread: an interrupt ends the read. */
internal object InterruptibleHttp {
    fun <T> read(call: Call, decode: (Response) -> T): T {
        checkInterrupted()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<T>?>(null)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.set(Result.failure(e))
                done.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    result.set(runCatching {
                        response.use {
                            if (call.isCanceled()) throw InterruptedIOException("Metadata HTTP call cancelled")
                            decode(it)
                        }
                    })
                } finally {
                    done.countDown()
                }
            }
        })
        try {
            done.await()
            checkInterrupted()
        } catch (t: InterruptedException) {
            call.cancel()
            // Extractor optional-field catches must not start another request after this interruption.
            Thread.currentThread().interrupt()
            throw t
        }
        return (result.get() ?: error("Metadata HTTP callback completed without a result")).getOrThrow()
    }
}

internal fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Request interrupted")
}
