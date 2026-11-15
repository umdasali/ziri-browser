package com.ziribrowser.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ziribrowser.ui.GlassSurface
import dev.chrisbanes.haze.HazeState

/** One destination in the bottom bar. [badge] is shown as a small count on the icon when above zero. */
data class NavItem(val route: String, val label: String, val icon: ImageVector, val badge: Int = 0)

/**
 * Floating glass bar. Only the selected destination shows its name, inside a tinted pill that slides
 * open; the others are icon-only, so the bar stays compact and easy to read at a glance.
 */
@Composable
fun ZiriNavBar(items: List<NavItem>, selectedRoute: String?, haze: HazeState, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier, shape = CircleShape, strong = true, haze = haze) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item -> NavBarItem(item, item.route == selectedRoute) { onSelect(item.route) } }
        }
    }
}

@Composable
private fun NavBarItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.78f)
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (selected) 16.dp else 14.dp)
            .animateContentSize(tween(250)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgedBox(badge = { if (item.badge > 0) Badge { Text("${item.badge}") } }) {
            Icon(item.icon, item.label, tint = tint)
        }
        AnimatedVisibility(selected, enter = fadeIn(tween(200)) + expandHorizontally(tween(250)), exit = fadeOut(tween(100)) + shrinkHorizontally(tween(250))) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(item.label, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
