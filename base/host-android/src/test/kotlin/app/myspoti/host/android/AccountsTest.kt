package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AccountsTest {
    private val default = AccountList.DEFAULT_PROFILE

    /** An install from before: one account, signed in on the default profile. */
    private fun oneAccount() = AccountList().signedIn(default, "k1", "Ana", "https://i/ana", now = 10).first

    @Test fun `an install from before keeps its account on the default profile`() {
        val list = AccountList()
        assertEquals(default, list.active)
        assertNull(list.current)
        val signed = oneAccount()
        assertEquals("Ana", signed.current?.name)
        assertTrue(signed.current!!.known)
        assertTrue(signed.others.isEmpty())
    }

    @Test fun `adding makes a new empty profile the active one, the previous one first among the others`() {
        val (list, name) = oneAccount().adding(now = 20)
        assertEquals("account-1", name)
        assertEquals(name, list.active)
        assertFalse(list.current!!.known)
        assertEquals(listOf(default), list.others.map { it.profile })
        assertEquals(20, list.accounts.first { it.profile == default }.usedAt)
        assertEquals(2, list.next)
    }

    @Test fun `a profile name is never reused, even one still being deleted`() {
        val (a, first) = oneAccount().adding(now = 20)
        val signed = a.signedIn(first, "k2", "Bo", null, now = 21).first.switchTo(default, now = 22).removed(first)
        assertEquals(listOf(first), signed.doomed)
        val (_, second) = signed.adding(now = 23)
        assertEquals("account-2", second)
    }

    @Test fun `switching changes the active account and remembers when each was used`() {
        val (a, p) = oneAccount().adding(now = 20)
        val b = a.signedIn(p, "k2", "Bo", null, now = 30).first
        val back = b.switchTo(default, now = 40)
        assertEquals(default, back.active)
        assertEquals(40, back.current!!.usedAt)
        assertEquals(40, back.accounts.first { it.profile == p }.usedAt)
        assertEquals(listOf(p), back.others.map { it.profile })
    }

    @Test fun `switching to a profile not kept changes nothing`() {
        val list = oneAccount()
        assertEquals(list, list.switchTo("account-9", now = 50))
    }

    @Test fun `the same account signed in twice is one account, in the profile just signed in`() {
        val (a, p) = oneAccount().adding(now = 20)
        val (list, dropped) = a.signedIn(p, "k1", "Ana", null, now = 30)
        assertEquals(listOf(default), dropped)
        assertEquals(listOf(p), list.accounts.map { it.profile })
        // The default profile cannot be deleted: it is cleared (purge) and listed to be.
        assertEquals(listOf(default), list.doomed)
        assertEquals("Ana", list.current!!.name)
        assertEquals("k1", list.current!!.key)
    }

    @Test fun `another account signed in on a profile does not inherit the previous one's name, picture or plan`() {
        val list = oneAccount().plan(default, true).signedOut(default)
        assertNull(list.current!!.key)
        val again = list.signedIn(default, "k3", "Cy", null, now = 40).first
        assertEquals("Cy", again.current!!.name)
        assertNull(again.current!!.imageUrl)
        assertNull(again.current!!.premium)
    }

    @Test fun `the plan is kept per account and an unknown answer keeps the known one`() {
        val list = oneAccount().plan(default, false)
        assertEquals(false, list.current!!.premium)
        assertEquals(false, list.plan(default, null).current!!.premium)
        assertEquals(true, list.plan(default, true).current!!.premium)
    }

    @Test fun `the active account is never removed, another one is`() {
        val (a, p) = oneAccount().adding(now = 20)
        assertEquals(a, a.removed(p))
        val removed = a.removed(default)
        assertEquals(listOf(p), removed.accounts.map { it.profile })
        assertEquals(listOf(default), removed.doomed)
        assertTrue(removed.deleted(default).doomed.isEmpty())
    }

    @Test fun `the list survives a save and a read`() {
        val (a, p) = oneAccount().plan(default, true).adding(now = 20)
        val list = a.signedIn(p, "k2", "Bo \"B\"", "https://i/bo", now = 30).first.removed(default).let { it.copy(doomed = it.doomed + "account-7") }
        assertEquals(list, AccountList.fromJson(list.toJson()))
    }

    @Test fun `an unreadable or missing list is an empty one on the default profile`() {
        assertEquals(AccountList(), AccountList.fromJson(null))
        assertEquals(AccountList(), AccountList.fromJson("{not json"))
        // An active profile that is not in the list: the one used last instead.
        val read = AccountList.fromJson("""{"active":"gone","accounts":[{"profile":"account-1","used":5},{"profile":"account-2","used":9}]}""")
        assertEquals("account-2", read.active)
    }

    @Test fun `the store saves every change and reads it back`() {
        val dir = Files.createTempDirectory("accounts").toFile()
        try {
            val file = java.io.File(dir, "spotify/accounts.json")
            val store = AccountStore(file)
            store.update { it.signedIn(default, "k1", "Ana", null, now = 1).first }
            store.update { it.adding(now = 2).first }
            assertEquals(store.list.value, AccountStore(file).list.value)
            assertEquals("account-1", AccountStore(file).list.value.active)
        } finally {
            dir.deleteRecursively()
        }
    }
}
