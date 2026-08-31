package org.koitharu.kotatsu.details.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemChapterBinding
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

fun chapterListItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
	onDownloadClick: (ChapterListItem) -> Unit,
	accentColorProvider: () -> Int? = { null },
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterBinding>(
	viewBinding = { inflater, parent -> ItemChapterBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	itemView.isFocusable = false
	itemView.isFocusableInTouchMode = false

	binding.imageButtonDownload.setOnClickListener {
		if (!item.isDownloaded) {
			onDownloadClick(item)
		}
	}

	bind {
		binding.textViewTitle.text = item.getTitle(context.resources)
		binding.textViewDescription.textAndVisible = item.description
		when {
			item.isCurrent -> {
				val accent = accentColorProvider()
					?: context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
				val radius = context.resources.displayMetrics.density * 2f
				binding.viewCurrentIndicator.background = GradientDrawable().apply {
					shape = GradientDrawable.RECTANGLE
					cornerRadius = radius
					setColor(accent)
				}
				binding.viewCurrentIndicator.isVisible = true
				binding.textViewTitle.drawableStart = null
				binding.textViewTitle.setTextColor(accent)
				binding.textViewDescription.setTextColor(accent)
				binding.textViewTitle.typeface = Typeface.DEFAULT_BOLD
				binding.textViewDescription.typeface = Typeface.DEFAULT_BOLD
				binding.textViewTitle.textSize = 17f
			}

			item.isUnread -> {
				binding.viewCurrentIndicator.background = null
				binding.viewCurrentIndicator.isVisible = false
				binding.textViewTitle.drawableStart = if (item.isNew) {
					ContextCompat.getDrawable(context, R.drawable.ic_new)
				} else {
					null
				}
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(materialR.attr.colorOutline))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewDescription.typeface = Typeface.DEFAULT
				binding.textViewTitle.textSize = 16f
			}

			else -> {
				binding.viewCurrentIndicator.background = null
				binding.viewCurrentIndicator.isVisible = false
				binding.textViewTitle.drawableStart = null
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewDescription.typeface = Typeface.DEFAULT
				binding.textViewTitle.textSize = 16f
			}
		}
		binding.imageViewBookmarked.isVisible = item.isBookmarked
		// Bookmark indicator follows the cover accent (falls back to the theme primary).
		binding.imageViewBookmarked.imageTintList = accentColorProvider()?.let { ColorStateList.valueOf(it) }
			?: context.getThemeColorStateList(androidx.appcompat.R.attr.colorPrimary)
		binding.imageViewDownloaded.isVisible = item.isDownloaded
		binding.imageButtonDownload.isVisible = !item.isDownloaded
	}
}

