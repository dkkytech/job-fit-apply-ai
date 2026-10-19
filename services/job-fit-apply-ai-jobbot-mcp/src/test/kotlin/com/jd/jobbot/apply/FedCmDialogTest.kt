package com.jd.jobbot.apply

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Chrome's FedCm.dialogShown payload, read defensively: odd shapes hand off, never throw. */
class FedCmDialogTest {
    private fun read(json: String, picked: MutableList<Pair<String, Int>> = mutableListOf()) =
        PlaywrightApplyBrowser.fedCmDialog(JsonParser.parseString(json).asJsonObject) { id, i -> picked += id to i }

    @Test
    fun `an account chooser lists emails in the dialog's own order`() {
        val picked = mutableListOf<Pair<String, Int>>()
        val d = read("""{"dialogId":"d1","dialogType":"AccountChooser","accounts":[{"email":"a@x.com"},{"email":"dkkytech@gmail.com"}]}""", picked)
        assertEquals("AccountChooser", d.dialogType)
        assertEquals(listOf("a@x.com", "dkkytech@gmail.com"), d.accountEmails)
        d.select(1)
        assertEquals(listOf("d1" to 1), picked)
    }

    @Test
    fun `a missing dialogId is an unreadable dialog, not a crash`() {
        val d = read("""{"dialogType":"AccountChooser","accounts":[{"email":"dkkytech@gmail.com"}]}""")
        assertEquals(PlaywrightApplyBrowser.UNREADABLE_DIALOG, d.dialogType)
        assertEquals(emptyList(), d.accountEmails)
    }

    @Test
    fun `null and odd account entries keep their positions`() {
        val d = read("""{"dialogId":"d2","dialogType":"AccountChooser","accounts":[{"email":null},"junk",{"name":"no email"},{"email":"dkkytech@gmail.com"}]}""")
        assertEquals(listOf("", "", "", "dkkytech@gmail.com"), d.accountEmails)
    }

    @Test
    fun `accounts of the wrong type and a null dialogType are tolerated`() {
        val d = read("""{"dialogId":"d3","dialogType":null,"accounts":{"email":"dkkytech@gmail.com"}}""")
        assertEquals("", d.dialogType)
        assertEquals(emptyList(), d.accountEmails)
    }
}
