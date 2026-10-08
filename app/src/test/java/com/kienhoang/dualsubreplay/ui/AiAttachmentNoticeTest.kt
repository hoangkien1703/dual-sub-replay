package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAttachment
import com.kienhoang.dualsubreplay.assistant.AiAttachmentKind
import com.kienhoang.dualsubreplay.assistant.AiAttachmentProblem
import com.kienhoang.dualsubreplay.assistant.AiAttachmentRead
import com.kienhoang.dualsubreplay.assistant.MAX_AI_ATTACHMENTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiAttachmentNoticeTest {
    private val added = AiAttachmentRead.Added(AiAttachment("page.jpg", AiAttachmentKind.PICTURE, "data:image/jpeg;base64,AAAA"))

    @Test
    fun aRefusedFileIsNamedWithItsReason() {
        val reads = listOf(added, AiAttachmentRead.Refused("clip.mp4", AiAttachmentProblem.UNSUPPORTED))
        assertEquals(AiAttachmentNotice(R.string.ai_attachment_unsupported, "clip.mp4"), aiAttachmentNotice(reads, allFit = true))
        assertEquals(
            AiAttachmentNotice(R.string.ai_attachment_too_big, "book.pdf"),
            aiAttachmentNotice(listOf(AiAttachmentRead.Refused("book.pdf", AiAttachmentProblem.TOO_BIG)), allFit = true),
        )
    }

    @Test
    fun filesOverTheLimitAreMentionedAndAddedFilesNeedNoNotice() {
        assertEquals(
            AiAttachmentNotice(R.string.ai_attachment_limit, MAX_AI_ATTACHMENTS),
            aiAttachmentNotice(listOf(added), allFit = false),
        )
        assertNull(aiAttachmentNotice(listOf(added), allFit = true))
    }
}
