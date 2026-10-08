package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiAttachment
import com.kienhoang.dualsubreplay.assistant.AiAttachmentKind
import com.kienhoang.dualsubreplay.assistant.AiAttachmentProblem
import com.kienhoang.dualsubreplay.assistant.AiAttachmentRead
import com.kienhoang.dualsubreplay.assistant.AiAttachmentReader
import com.kienhoang.dualsubreplay.assistant.MAX_AI_ATTACHMENTS
import com.kienhoang.dualsubreplay.assistant.aiPictureThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ThumbnailSize = 36.dp
private val AttachmentNameMaxWidth = 140.dp

/** Why chosen files were not all added: a message with the file's name or the limit. */
internal data class AiAttachmentNotice(
    @StringRes val messageRes: Int,
    val argument: Any,
)

internal fun AiAttachmentProblem.messageRes(): Int =
    when (this) {
        AiAttachmentProblem.TOO_BIG -> R.string.ai_attachment_too_big
        AiAttachmentProblem.UNSUPPORTED -> R.string.ai_attachment_unsupported
        AiAttachmentProblem.UNREADABLE -> R.string.ai_attachment_unreadable
    }

/** The first file that could not be added, else the limit when some did not fit, else nothing. */
internal fun aiAttachmentNotice(
    reads: List<AiAttachmentRead>,
    allFit: Boolean,
): AiAttachmentNotice? {
    val refused = reads.filterIsInstance<AiAttachmentRead.Refused>().firstOrNull()
    return when {
        refused != null -> AiAttachmentNotice(refused.problem.messageRes(), refused.name)
        !allFit -> AiAttachmentNotice(R.string.ai_attachment_limit, MAX_AI_ATTACHMENTS)
        else -> null
    }
}

/**
 * The chat box: chosen pictures and files above it, and under it the + button with the model and
 * thinking level. A question may be only pictures or files; it then asks to explain them.
 */
@Composable
internal fun AiComposer(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    onSend: (String, List<AiAttachment>) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<AiAttachmentNotice?>(null) }
    val drafts = aiState.draftAttachments
    val picker = rememberAiAttachmentPicker(controller) { notice = it }
    val defaultQuestion = stringResource(R.string.ai_attachment_question)
    Column(Modifier.fillMaxWidth()) {
        notice?.let {
            Text(
                stringResource(it.messageRes, it.argument),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("ai_attachment_notice"),
            )
        }
        if (drafts.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp)
                    .testTag("ai_draft_attachments"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                drafts.forEachIndexed { index, file -> AiAttachmentChip(file, onRemove = { controller.removeAttachment(index) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).testTag("ai_input"),
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
            )
            IconButton(
                onClick = {
                    onSend(text.ifBlank { defaultQuestion }, drafts)
                    text = ""
                    notice = null
                },
                enabled = !aiState.sending && (text.isNotBlank() || drafts.isNotEmpty()),
                modifier = Modifier.testTag("ai_send_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.ai_send))
            }
        }
        AiChatOptionsBar(aiState, controller) {
            AiAttachMenu(enabled = !aiState.sending && drafts.size < MAX_AI_ATTACHMENTS, picker)
        }
    }
}

private class AiAttachmentPicker(
    val pickPhotos: () -> Unit,
    val pickFiles: () -> Unit,
)

/** The system photo picker and file picker; what they return is read off the main thread, then added. */
@Composable
private fun rememberAiAttachmentPicker(
    controller: AiAssistantController,
    onNotice: (AiAttachmentNotice?) -> Unit,
): AiAttachmentPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember(context) { AiAttachmentReader(context.contentResolver) }
    val add: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            onNotice(null)
            scope.launch {
                val reads = withContext(Dispatchers.IO) { uris.map(reader::read) }
                val allFit = controller.addAttachments(reads.filterIsInstance<AiAttachmentRead.Added>().map { it.attachment })
                onNotice(aiAttachmentNotice(reads, allFit))
            }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_AI_ATTACHMENTS), add)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), add)
    return AiAttachmentPicker(
        pickPhotos = {
            launchSafely { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        },
        pickFiles = { launchSafely { files.launch(arrayOf("*/*")) } },
    )
}

/** A phone without a file picker app cannot attach anything, but must not crash. */
private inline fun launchSafely(launch: () -> Unit) {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
private fun AiAttachMenu(
    enabled: Boolean,
    picker: AiAttachmentPicker,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("ai_attach_button")) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ai_attach))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_attach_photo)) },
                onClick = {
                    open = false
                    picker.pickPhotos()
                },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                modifier = Modifier.testTag("ai_attach_photo"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_attach_file)) },
                onClick = {
                    open = false
                    picker.pickFiles()
                },
                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                modifier = Modifier.testTag("ai_attach_file"),
            )
        }
    }
}

/**
 * A picture or file: a small picture or an icon, then its name. With [onRemove] it is a chosen
 * file under the chat box; without, it sits in a sent message.
 */
@Composable
internal fun AiAttachmentChip(
    attachment: AiAttachment,
    onRemove: (() -> Unit)?,
) {
    val draft = onRemove != null
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (draft) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        border = if (draft) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag("ai_attachment"),
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = if (draft) 0.dp else 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val picture = rememberAiThumbnail(attachment)
            if (picture != null) {
                Image(
                    picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(ThumbnailSize).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(attachment.kind.icon(), contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = AttachmentNameMaxWidth),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp).testTag("ai_attachment_remove")) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.ai_attachment_remove, attachment.name),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private fun AiAttachmentKind.icon(): ImageVector =
    when (this) {
        AiAttachmentKind.PICTURE -> Icons.Default.Image
        AiAttachmentKind.PDF -> Icons.Default.PictureAsPdf
        AiAttachmentKind.TEXT -> Icons.Default.Description
    }

/** The picture, small, decoded off the main thread; null for files and for pictures no longer kept. */
@Composable
private fun rememberAiThumbnail(attachment: AiAttachment): ImageBitmap? {
    val sizePx = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
    val thumbnail by produceState<ImageBitmap?>(null, attachment.data, sizePx) {
        if (attachment.kind == AiAttachmentKind.PICTURE && attachment.available) {
            value = withContext(Dispatchers.Default) { aiPictureThumbnail(attachment.data, sizePx)?.asImageBitmap() }
        }
    }
    return thumbnail
}
