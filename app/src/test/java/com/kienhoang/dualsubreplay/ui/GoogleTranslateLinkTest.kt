package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleTranslateLinkTest {
    @Test
    fun resultNamesTheFirstMissingStep() {
        assertEquals(TranslateSendResult.NOT_INSTALLED, translateSendResult(installed = false, hasReceiver = false))
        assertEquals(TranslateSendResult.CANNOT_RECEIVE, translateSendResult(installed = true, hasReceiver = false))
        assertEquals(TranslateSendResult.SENT, translateSendResult(installed = true, hasReceiver = true))
    }

    @Test
    fun blankTextBuildsNoIntent() {
        assertEquals(null, googleTranslateSendIntent(""))
        assertEquals(null, googleTranslateSendIntent("  \n"))
    }
}
