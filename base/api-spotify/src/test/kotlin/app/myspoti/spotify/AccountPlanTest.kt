package app.myspoti.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plan of an accountAttributes answer; the fixtures are real answers with personal fields replaced. */
class AccountPlanTest {
    private fun fixture(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() })

    private fun answer(product: String?, ads: String?): JsonElement {
        val fields = listOfNotNull(
            product?.let { "\"product\":\"$it\"" },
            "\"attributes\":" + (ads?.let { "{\"ads\":$it}" } ?: "{}"),
        )
        return Json.parseToJsonElement("""{"data":{"me":{"account":{${fields.joinToString(",")}}}}}""")
    }

    @AfterEach fun forget() = AccountPlan.set(null)

    @Test fun `the Premium answer is Premium`() = assertEquals(true, AccountPlan.fromAnswer(fixture("account-premium.json")))

    @Test fun `the free answer is free, with its ads on`() = assertEquals(false, AccountPlan.fromAnswer(fixture("account-free.json")))

    @Test fun `a paid product with its ads off is Premium, whatever the paid plan is called`() {
        for (product in listOf("PREMIUM", "premium", "DUO", "FAMILY", "STUDENT"))
            assertEquals(true, AccountPlan.fromAnswer(answer(product, "false")), product)
    }

    @Test fun `free and open products are free, whatever their ads say`() {
        for (product in listOf("FREE", "free", "OPEN")) {
            assertEquals(false, AccountPlan.fromAnswer(answer(product, "false")), product)
            assertEquals(false, AccountPlan.fromAnswer(answer(product, "true")), product)
        }
    }

    @Test fun `a paid product that still gets ads is not Premium here`() =
        assertEquals(false, AccountPlan.fromAnswer(answer("PREMIUM", "true")))

    @Test fun `an answer that does not say is unknown`() {
        assertNull(AccountPlan.fromAnswer(answer(null, "false")))
        assertNull(AccountPlan.fromAnswer(answer("PREMIUM", null)))
        assertNull(AccountPlan.fromAnswer(answer("  ", "false")))
        assertNull(AccountPlan.fromAnswer(Json.parseToJsonElement("""{"data":{"me":{}}}""")))
        assertNull(AccountPlan.fromAnswer(Json.parseToJsonElement("""{"errors":[{"message":"x"}]}""")))
        assertNull(AccountPlan.fromAnswer(null))
    }

    @Test fun `the plan is unknown until set, and forgotten when set back`() {
        assertNull(AccountPlan.premium.value)
        assertFalse(AccountPlan.isPremium)
        AccountPlan.set(true)
        assertTrue(AccountPlan.isPremium)
        AccountPlan.set(null)
        assertNull(AccountPlan.premium.value)
        assertFalse(AccountPlan.isPremium)
    }
}
