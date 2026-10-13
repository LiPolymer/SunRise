package ink.lipoly.app.sunrise.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ListItemCard(modifier: Modifier = Modifier,
                          overrideModifier: Boolean = false,
                          content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = if (overrideModifier) modifier
                    else modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth(),
        content = content
    )
}

/** A card section with a keyboard-accessible header and coordinated Expressive expansion motion. */
@Composable
internal fun Expander(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val transition = updateTransition(expanded, label = "Expander")
    val rotation by transition.animateFloat(
        transitionSpec = { motion.fastSpatialSpec() },
        label = "Expander arrow",
    ) { if (it) 180f else 0f }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起$title" else "展开$title",
                    onClick = { onExpandedChange(!expanded) },
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        transition.AnimatedVisibility(
            visible = { it },
            enter = expandVertically(
                animationSpec = motion.defaultSpatialSpec(),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = motion.fastEffectsSpec()),
            exit = shrinkVertically(
                animationSpec = motion.defaultSpatialSpec(),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = motion.fastEffectsSpec()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** Connected single-choice controls; scroll instead of compressing labels at larger font scales. */
@Composable
internal fun <T> SingleChoiceButtons(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) onSelect(option) },
                enabled = enabled,
                shapes = when {
                    options.size == 1 -> ToggleButtonDefaults.shapes()
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    role = Role.RadioButton
                    this.selected = checked
                },
            ) { Text(label(option),
                modifier = textModifier,
                textAlign = TextAlign.Center) }
        }
    }
}

@Composable
internal fun BatteryCell(label: String, percent: Int?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineSmallEmphasized)
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
