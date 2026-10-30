package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/**
 * The X that closes a dialog (owner request 2026-10-30): the same as tapping outside, Back or the
 * dialog's Cancel, never saving, deleting or starting anything. 48 dp to tap, "Close" for screen readers.
 */
@Composable
internal fun CloseIconButton(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = DIALOG_CLOSE_TAG,
) {
    IconButton(onClick = onClose, modifier = modifier.testTag(tag)) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dialog_close))
    }
}

internal const val DIALOG_CLOSE_TAG = "dialog_close"

/**
 * An `AlertDialog` title with the X at its top right. Dialogs the person must answer (the security
 * update, deleting or clearing something, resetting settings) keep a plain title and no X.
 */
@Composable
internal fun DialogTitle(
    text: String,
    onClose: () -> Unit,
    closeTag: String = DIALOG_CLOSE_TAG,
    /** For a dialog with an icon, whose title Material centers under it. */
    centered: Boolean = false,
) {
    Row(verticalAlignment = Alignment.Top) {
        // Balances the X so a centered title stays in the middle.
        if (centered) Spacer(Modifier.width(36.dp))
        Text(
            text,
            modifier = Modifier.weight(1f).padding(top = 6.dp, end = 4.dp),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        // Pulled into the dialog's padding so the X sits near the corner, as on other Android dialogs.
        CloseIconButton(onClose, Modifier.offset(x = 12.dp, y = (-6).dp), closeTag)
    }
}
