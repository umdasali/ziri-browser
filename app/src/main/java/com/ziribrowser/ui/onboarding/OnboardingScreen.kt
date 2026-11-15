package com.ziribrowser.ui.onboarding

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ziribrowser.data.Interest
import com.ziribrowser.ui.GlassSurface

/** Shown once, on first launch: pick topics so Home can suggest sites and show their latest headlines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(initial: Set<Interest> = emptySet(), onDone: (Set<Interest>) -> Unit) {
    var chosen by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.welcome_to_ziri), modifier = Modifier.padding(top = 36.dp).semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.what_are_you_into), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.pick_a_few_topics_your_home_screen_will),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Interest.entries.forEach { i ->
                    InterestChip(i, i in chosen) { chosen = if (i in chosen) chosen - i else chosen + i }
                }
            }
            Text(
                stringResource(R.string.your_picks_stay_on_this_device_headlines),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onDone(emptySet()) }) { Text(stringResource(R.string.skip_for_now)) }
            Box(Modifier.weight(1f))
            Button({ onDone(chosen) }, enabled = chosen.isNotEmpty()) { Text(if (chosen.isEmpty()) stringResource(R.string.pick_at_least_one) else stringResource(R.string.continue_text_2, chosen.size)) }
        }
    }
}

@Composable
fun InterestChip(interest: Interest, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, label = "chip")
    GlassSurface(
        Modifier.semantics(mergeDescendants = true) { role = Role.Checkbox; stateDescription = if (selected) ziriString(R.string.selected) else ziriString(R.string.not_selected) },
        shape = CircleShape, highlight = selected, tint = tint, onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(interest.emoji, style = MaterialTheme.typography.titleMedium)
            Text("  ${interest.label}", fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
