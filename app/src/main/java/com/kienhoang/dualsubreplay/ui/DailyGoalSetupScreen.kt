package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.DEFAULT_DAILY_GOAL_MINUTES

/** One-time step after the guide. [onFinish] receives the chosen minutes, or null when skipped. */
@Composable
fun DailyGoalSetupScreen(onFinish: (Int?) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(DEFAULT_DAILY_GOAL_MINUTES) }
    Surface(
        modifier = Modifier.fillMaxSize().testTag("daily_goal_setup"),
        color = Color(0xFF061719),
        contentColor = Color(0xFFF3FAFA),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onFinish(null) }, modifier = Modifier.testTag("daily_goal_skip")) {
                    Text(stringResource(R.string.onboarding_skip), color = Color(0xFFB7CED1))
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.onboarding_goal_setup_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFF3FAFA),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.onboarding_goal_setup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB7CED1),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                DailyGoalPicker(selected = selected, includeOff = false, onSelect = { selected = it }, centered = true)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onFinish(selected) }, modifier = Modifier.fillMaxWidth().testTag("daily_goal_confirm")) {
                Text(stringResource(R.string.onboarding_goal_set))
            }
        }
    }
}
