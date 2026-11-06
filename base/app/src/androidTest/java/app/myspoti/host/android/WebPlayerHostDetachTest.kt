package app.myspoti.host.android

import android.content.MutableContextWrapper
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The process-wide host must release a destroyed Activity's root, including after reattachment. */
@RunWith(AndroidJUnit4::class)
class WebPlayerHostDetachTest {
    @Test fun detachReleasesItsOwnerAndIgnoresAnOlderOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        lateinit var host: WebPlayerHost
        lateinit var first: FrameLayout
        lateinit var second: FrameLayout
        instrumentation.runOnMainSync {
            host = WebPlayerHost(app)
            first = FrameLayout(app)
            second = FrameLayout(app)
            host.attach(first, visible = false)
        }
        instrumentation.waitForIdleSync()
        val container = WebPlayerHost::class.java.getDeclaredField("container").apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync { host.detach(first) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNull("the destroyed root must not remain in the process-wide host", container.get(host))
                assertNull(host.view.parent)
                assertSame(app, (host.view.context as MutableContextWrapper).baseContext)
                host.attach(first, visible = false)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { host.attach(second, visible = false) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { host.detach(first) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertSame("an old Activity must not detach the new Activity's view", second, host.view.parent)
                assertSame(second, container.get(host))
                host.detach(second)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNull(container.get(host))
                assertNull(host.view.parent)
            }
        } finally {
            instrumentation.runOnMainSync { host.view.destroy() }
        }
    }
}
