package org.koitharu.kotatsu.list.ui.adapter

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.image.FaviconView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeResId
import org.koitharu.kotatsu.core.util.ext.resolveDp
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import kotlin.math.ceil
import com.google.android.material.R as materialR
import androidx.appcompat.R as appcompatR

internal object ExtensionFilterPopup {

	fun show(
		anchor: View,
		filter: ExtensionFilter,
		listener: QuickFilterClickListener,
	) {
		val context = anchor.context
		val popupWidth = resolvePopupWidth(context, filter)
		val rows = ArrayList<Row>(filter.options.size)
		val selectedSourceNames = filter.selectedOptions.mapTo(HashSet()) { it.mangaSource.name }
		val resetButton = createResetButton(context)
		var popupWindow: PopupWindow? = null
		val dismissPopup = { popupWindow?.dismiss(); Unit }
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
			addView(
				createHeader(context, if (filter.isAdvanced) R.string.favorites_filter else R.string.extension_filters),
				LinearLayout.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)

			if (filter.isAdvanced) {
				addView(createSectionHeader(context, R.string.favorites_reading_progress))
				addChoiceRows(
					context = context,
					selected = filter.readingProgress,
					options = ListFilterOption.ReadingProgress.entries.toList(),
					onSelection = { option ->
						listener.onFilterOptionsCleared(ListFilterOption.ReadingProgress.entries.toList())
						if (option != null) listener.onFilterOptionChanged(option, true)
						dismissPopup()
					},
				)

				addView(createSectionHeader(context, R.string.favorites_publication_status))
				val stateOptions = ListFilterOption.State.CYCLE.map { ListFilterOption.State(it) }
				addChoiceRows(
					context = context,
					selected = filter.publicationState,
					options = stateOptions,
					onSelection = { option ->
						listener.onFilterOptionsCleared(stateOptions)
						if (option != null) listener.onFilterOptionChanged(option, true)
						dismissPopup()
					},
				)
			}

			if (filter.isAdvanced) {
				addView(createSectionHeader(context, R.string.favorites_source))
			}
			if (filter.options.isNotEmpty() || filter.selectedOptions.isNotEmpty()) {
				if (filter.isAdvanced) {
					addView(createAllSourcesRow(context, selectedSourceNames.isEmpty()) {
						selectedSourceNames.clear()
						rows.forEach { it.checkBox.isChecked = false }
						listener.onFilterOptionsCleared(filter.options + filter.selectedOptions)
						dismissPopup()
					})
				}
				for (option in filter.options) {
					val row = createRow(
						context = context,
						option = option,
						selectedSourceNames = selectedSourceNames,
						listener = listener,
						onSelectionChanged = {
							updateResetButton(resetButton, filter.isAdvanced || selectedSourceNames.isNotEmpty())
						},
					)
					rows += row.binding
					addView(row.view, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
				}
			} else if (filter.isAdvanced) {
				addView(createUnavailableSourcesRow(context))
			}

			addView(
				resetButton,
				LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
					topMargin = context.resources.resolveDp(8)
					bottomMargin = context.resources.resolveDp(12)
					marginStart = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
					marginEnd = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
				},
			)
		}

		resetButton.setOnClickListener {
			val allOptions = buildList<ListFilterOption> {
				addAll(filter.options)
				addAll(filter.selectedOptions)
				if (filter.isAdvanced) {
					addAll(ListFilterOption.ReadingProgress.entries)
					addAll(ListFilterOption.State.CYCLE.map { ListFilterOption.State(it) })
				}
			}
			listener.onFilterOptionsCleared(allOptions)
			popupWindow?.dismiss()
		}
		updateResetButton(resetButton, filter.isActive)

		val scrollView = MaxHeightScrollView(context).apply {
			maxHeight = context.resources.resolveDp(520)
			addView(content, ViewGroup.LayoutParams(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
			isFillViewport = false
			clipToPadding = false
		}
		popupWindow = PopupWindow(scrollView, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
			setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.m3_menu_background))
			isOutsideTouchable = true
			elevation = context.resources.resolveDp(8).toFloat()
			showAsDropDown(anchor, 0, context.resources.resolveDp(4), Gravity.NO_GRAVITY)
		}
	}

	private fun LinearLayout.addChoiceRows(
		context: Context,
		selected: ListFilterOption?,
		options: List<ListFilterOption>,
		onSelection: (ListFilterOption?) -> Unit,
	) {
		addView(createChoiceRow(context, R.string.favorites_all, selected == null) { onSelection(null) })
		for (option in options) {
			addView(createChoiceRow(context, option.titleResId, selected == option) { onSelection(option) })
		}
	}

	private fun createChoiceRow(
		context: Context,
		@StringRes titleResId: Int,
		isChecked: Boolean,
		onClick: () -> Unit,
	): View = MaterialRadioButton(context).apply {
		setText(titleResId)
		this.isChecked = isChecked
		minimumHeight = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_min_height)
		setPadding(
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
			0,
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
			0,
		)
		setOnClickListener { onClick() }
	}

	private fun createAllSourcesRow(context: Context, isChecked: Boolean, onClick: () -> Unit): View =
		createChoiceRow(context, R.string.favorites_all, isChecked, onClick)

	private fun createUnavailableSourcesRow(context: Context): View = TextView(context).apply {
		setText(R.string.favorites_no_sources)
		setTextColor(context.getThemeColor(android.R.attr.textColorSecondary, Color.GRAY))
		setPadding(
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
			context.resources.resolveDp(8),
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
			context.resources.resolveDp(12),
		)
	}

	private fun createHeader(context: Context, @StringRes titleResId: Int): View = TextView(context).apply {
		setText(titleResId)
		setTextAppearanceAttr(materialR.attr.textAppearanceTitleMedium)
		setTextColor(context.getThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
		gravity = Gravity.CENTER_VERTICAL
		minimumHeight = context.resources.resolveDp(48)
		setPadding(
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal), 0,
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal), 0,
		)
	}

	private fun createSectionHeader(context: Context, @StringRes titleResId: Int): View = TextView(context).apply {
		setText(titleResId)
		setTextAppearanceAttr(materialR.attr.textAppearanceLabelLarge)
		setTextColor(context.getThemeColor(android.R.attr.textColorSecondary, Color.GRAY))
		setPadding(
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
			context.resources.resolveDp(10),
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
			context.resources.resolveDp(4),
		)
	}

	private fun createResetButton(context: Context): MaterialButton =
		MaterialButton(context, null, materialR.attr.materialButtonTonalStyle).apply {
			setText(R.string.reset)
			gravity = Gravity.CENTER
			minimumHeight = context.resources.resolveDp(48)
			minimumWidth = context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width)
		}

	private fun createRow(
		context: Context,
		option: ListFilterOption.Source,
		selectedSourceNames: MutableSet<String>,
		listener: QuickFilterClickListener,
		onSelectionChanged: () -> Unit,
	): RowView {
		val row = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_min_height)
			minimumWidth = context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width)
			setPadding(
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal), 0,
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal), 0,
			)
			background = ContextCompat.getDrawable(context, R.drawable.extension_filter_row_selector)
		}
		val icon = FaviconView(context).apply {
			applyExternalSourceStyle(option.mangaSource.isExternalSource())
			setImageAsync(option.mangaSource)
		}
		val title = TextView(context).apply {
			text = option.getMenuTitle(context)
			setTextAppearanceAttr(appcompatR.attr.textAppearanceLargePopupMenu)
			setTextColor(context.getThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
			isSingleLine = true
			ellipsize = TextUtils.TruncateAt.END
			maxWidth = context.resources.displayMetrics.widthPixels - context.resources.resolveDp(144)
		}
		val checkBox = MaterialCheckBox(context).apply {
			isClickable = false
			isFocusable = false
			isChecked = option.mangaSource.name in selectedSourceNames
			minimumWidth = 0
			minimumHeight = 0
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 0)
			background = null
		}
		row.addView(icon, LinearLayout.LayoutParams(
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size),
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size),
		).apply { marginEnd = context.resources.getDimensionPixelSize(R.dimen.menu_icon_text_spacing_extra) })
		row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
			marginEnd = context.resources.resolveDp(8)
		})
		row.addView(checkBox, LinearLayout.LayoutParams(
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size),
			context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_min_height),
		))
		val rowBinding = Row(checkBox)
		row.setOnClickListener {
			val checked = !checkBox.isChecked
			checkBox.isChecked = checked
			if (checked) selectedSourceNames += option.mangaSource.name else selectedSourceNames -= option.mangaSource.name
			listener.onFilterOptionChanged(option, checked)
			onSelectionChanged()
		}
		return RowView(row, rowBinding)
	}

	private fun resolvePopupWidth(context: Context, filter: ExtensionFilter): Int {
		val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
		val iconSize = context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size)
		val iconTextGap = context.resources.getDimensionPixelSize(R.dimen.menu_icon_text_spacing_extra)
		val textCheckGap = context.resources.resolveDp(8)
		val menuMinWidth = context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width)
		val maxAvailableWidth = context.resources.displayMetrics.widthPixels - context.resources.resolveDp(32)
		val widestSourceWidth = filter.options.maxOfOrNull {
			context.measureTextWidth(it.getMenuTitle(context), appcompatR.attr.textAppearanceLargePopupMenu)
		} ?: 0
		val sourceRowWidth = horizontalPadding + iconSize + iconTextGap + widestSourceWidth + textCheckGap + iconSize + horizontalPadding
		val headerRes = if (filter.isAdvanced) R.string.favorites_filter else R.string.extension_filters
		val headerWidth = horizontalPadding + context.measureTextWidth(
			context.getString(headerRes), materialR.attr.textAppearanceTitleMedium,
		) + horizontalPadding
		val advancedWidth = if (filter.isAdvanced) context.resources.resolveDp(280) else 0
		return maxOf(menuMinWidth, sourceRowWidth, headerWidth, advancedWidth).coerceAtMost(maxAvailableWidth)
	}

	private fun updateResetButton(resetButton: MaterialButton, isEnabled: Boolean) {
		resetButton.isEnabled = isEnabled
	}

	private fun ListFilterOption.Source.getMenuTitle(context: Context): String = when (val source = mangaSource.unwrap()) {
		is MihonMangaSource -> if (source.hasLanguageSuffix) {
			"${source.displayName} (${source.languageDisplayName})"
		} else source.displayName
		else -> mangaSource.getTitle(context)
	}

	private fun Context.measureTextWidth(text: CharSequence, @AttrRes textAppearanceAttr: Int): Int {
		val textView = TextView(this)
		textView.setTextAppearanceAttr(textAppearanceAttr)
		return ceil(textView.paint.measureText(text, 0, text.length)).toInt()
	}

	private fun TextView.setTextAppearanceAttr(@AttrRes attr: Int) {
		val style = context.getThemeResId(attr, 0)
		if (style != 0) setTextAppearance(style)
	}

	private class MaxHeightScrollView(context: Context) : ScrollView(context) {
		var maxHeight = 0
		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			val resolvedHeight = if (maxHeight > 0) MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) else heightMeasureSpec
			super.onMeasure(widthMeasureSpec, resolvedHeight)
		}
	}

	private data class Row(val checkBox: MaterialCheckBox)
	private data class RowView(val view: View, val binding: Row)
}
