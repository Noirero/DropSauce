package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareAccentSurface
import org.koitharu.kotatsu.core.ui.miyorareSurface
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter

/**
 * Settings row whose value is picked from a short, fixed set of options shown as an M3 Expressive
 * connected button group underneath the title — for choices that are too small to deserve a dialog
 * but need their whole range visible at a glance.
 *
 * @param labels one label per option; [selectedIndex] indexes into it.
 */
@Composable
fun SegmentedSettingsItem(
	title: String,
	labels: List<String>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	val visualPalette = LocalMiyorareVisualPalette.current
	val modern = visualPalette.isModern
	val iconColor = if (modern) {
		visualPalette.primary
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	val surfaceModifier = if (modern) {
		modifier.miyorareSurface(visualPalette, shape)
	} else {
		modifier
	}
	Surface(
		modifier = surfaceModifier,
		shape = shape,
		color = if (modern) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
		contentColor = MaterialTheme.colorScheme.onSurface,
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (icon != null) {
					Box(
						modifier = Modifier.size(if (modern) 40.dp else 44.dp),
						contentAlignment = Alignment.Center,
					) {
						androidx.compose.foundation.Image(
							painter = rememberAnyDrawablePainter(icon),
							contentDescription = null,
							modifier = Modifier.size(if (modern) 22.dp else 24.dp),
							colorFilter = ColorFilter.tint(
								iconColor.copy(alpha = if (enabled) 1f else 0.4f),
							),
						)
					}
					Spacer(Modifier.width(14.dp))
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = title,
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurface
							.copy(alpha = if (enabled) 1f else 0.38f),
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					if (!subtitle.isNullOrBlank()) {
						Text(
							text = subtitle,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
								.copy(alpha = if (enabled) 1f else 0.38f),
						)
					}
				}
			}
			Spacer(Modifier.height(12.dp))
			SegmentedRow(
				labels = labels,
				selectedIndex = selectedIndex,
				onSelected = onSelected,
				enabled = enabled,
			)
		}
	}
}

/**
 * Connected button group per the M3 Expressive spec: 2dp gaps, pill-shaped outer corners, 8dp
 * inner seams, and the selected segment swelling slightly as it takes the accent colour.
 */
@Composable
private fun SegmentedRow(
	labels: List<String>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	enabled: Boolean,
) {
	val haptic = rememberHapticEffect()
	val visualPalette = LocalMiyorareVisualPalette.current
	val modern = visualPalette.isModern
	val colorAnimation = when (visualPalette.effectLevel) {
		VisualEffectLevel.LIGHT -> snap<Color>()
		VisualEffectLevel.BALANCED -> tween<Color>(MiyorareVisualTokens.MOTION_QUICK_MS)
		VisualEffectLevel.FULL -> tween<Color>(MiyorareVisualTokens.MOTION_STANDARD_MS)
	}
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		labels.forEachIndexed { index, label ->
			val isSelected = index == selectedIndex
			val isFirst = index == 0
			val isLast = index == labels.lastIndex
			val segmentShape = RoundedCornerShape(
				topStart = if (isFirst) 50.dp else 8.dp,
				bottomStart = if (isFirst) 50.dp else 8.dp,
				topEnd = if (isLast) 50.dp else 8.dp,
				bottomEnd = if (isLast) 50.dp else 8.dp,
			)
			val alpha = if (enabled) 1f else 0.38f
			val targetBackground = if (isSelected) {
				MaterialTheme.colorScheme.primary.copy(alpha = alpha)
			} else {
				MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
			}
			val targetForeground = if (isSelected) {
				MaterialTheme.colorScheme.onPrimary.copy(alpha = alpha)
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
			}
			val background by if (modern) {
				animateColorAsState(
					targetValue = targetBackground,
					animationSpec = colorAnimation,
					label = "segment_bg_$index",
				)
			} else {
				animateColorAsState(targetValue = targetBackground, label = "segment_bg_$index")
			}
			val foreground by if (modern) {
				animateColorAsState(
					targetValue = targetForeground,
					animationSpec = colorAnimation,
					label = "segment_fg_$index",
				)
			} else {
				animateColorAsState(targetValue = targetForeground, label = "segment_fg_$index")
			}
			val scale by animateFloatAsState(
				targetValue = when {
					isSelected -> 1f
					!modern -> 0.94f
					visualPalette.effectLevel == VisualEffectLevel.LIGHT -> 1f
					else -> 0.96f
				},
				animationSpec = if (!modern) {
					spring(
						dampingRatio = Spring.DampingRatioMediumBouncy,
						stiffness = Spring.StiffnessMediumLow,
					)
				} else {
					when (visualPalette.effectLevel) {
						VisualEffectLevel.LIGHT -> snap<Float>()
						VisualEffectLevel.BALANCED -> tween(MiyorareVisualTokens.MOTION_QUICK_MS)
						VisualEffectLevel.FULL -> spring(
							dampingRatio = Spring.DampingRatioMediumBouncy,
							stiffness = Spring.StiffnessMediumLow,
						)
					}
				},
				label = "segment_scale_$index",
			)
			val segmentModifier = Modifier
				.weight(1f)
				.height(48.dp)
				.scale(scale)
				.let {
					if (modern && isSelected) {
						it.miyorareAccentSurface(
							palette = visualPalette,
							shape = segmentShape,
							alpha = alpha,
						)
					} else {
						it
					}
				}
			Surface(
				onClick = {
					haptic(HapticEffect.TOGGLE_ON)
					onSelected(index)
				},
				enabled = enabled,
				modifier = segmentModifier,
				shape = segmentShape,
				color = if (modern && isSelected) Color.Transparent else background,
				contentColor = foreground,
			) {
				Box(contentAlignment = Alignment.Center) {
					Text(
						text = label,
						style = MaterialTheme.typography.labelLarge,
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
						textAlign = TextAlign.Center,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 6.dp),
					)
				}
			}
		}
	}
}
