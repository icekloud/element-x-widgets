/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import android.graphics.Typeface
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayout
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.wysiwyg.link.Link
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Element June: render message bodies (Markdown) with Markwon, like Element classic.
 */
@Composable
internal fun MarkwonMessageText(
    markdown: String,
    onLinkClick: (Link) -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val textColor = ElementTheme.colors.textPrimary.toArgb()
    val linkColor = ElementTheme.colors.textLinkExternal.toArgb()
    val codeBackground = ElementTheme.colors.bgSubtleSecondary.toArgb()
    val textSizeSp = ElementTheme.typography.fontBodyLgRegular.fontSize.value
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val markwon = remember(context, linkColor, codeBackground) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            // No phone numbers: digits inside messages must not become links
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(linkColor)
                        .codeBackgroundColor(codeBackground)
                        .codeBlockBackgroundColor(codeBackground)
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeBlockTypeface(Typeface.MONOSPACE)
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(LinkResolver { _, link -> currentOnLinkClick(Link(url = link, text = link)) })
                }
            })
            .build()
    }
    val spanned = remember(markwon, markdown) { markwon.toMarkdown(markdown) }
    val measure = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange)
    val currentMeasure by rememberUpdatedState(measure)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkOnlyMovementMethod
                // Let the message bubble handle clicks and long clicks outside links
                isClickable = false
                isLongClickable = false
                isFocusable = false
                setLineSpacing(0f, 1.1f)
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    (view as TextView).layout?.let { currentMeasure(it) }
                }
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.textSize = textSizeSp
            markwon.setParsedMarkdown(textView, spanned)
        },
    )
}

/**
 * Only consume touches on links, so the bubble keeps receiving the other clicks and long clicks.
 */
private object LinkOnlyMovementMethod : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val layout = widget.layout ?: return false
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val hasLink = buffer.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
        return hasLink && super.onTouchEvent(widget, buffer, event)
    }
}
