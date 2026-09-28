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
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.spans.CodeBlockSpan
import org.commonmark.node.Code
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
    // Element June: one shared Markwon instance and a parsed text cache, so opening a room or
    // scrolling does not rebuild the parser and re-parse every message (source of frame drops).
    val markwon = remember(linkColor, codeBackground) {
        MarkwonCache.markwon(context.applicationContext, linkColor, codeBackground)
    }
    val spanned = remember(markwon, markdown) { MarkwonCache.parse(markwon, markdown) }
    val measure = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange)
    val currentMeasure by rememberUpdatedState(measure)
    val currentContentLayoutChange by rememberUpdatedState(onContentLayoutChange)
    // Element June: a code block background spans the TextView width, so let such messages use the whole bubble width
    val hasCodeBlock = remember(spanned) {
        spanned is android.text.Spanned && spanned.getSpans(0, spanned.length, CodeBlockSpan::class.java).isNotEmpty()
    }
    AndroidView(
        modifier = if (hasCodeBlock) modifier.fillMaxWidth() else modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTag(R_ID_LINK_HANDLER, { link: String -> currentOnLinkClick(Link(url = link, text = link)) })
                movementMethod = LinkOnlyMovementMethod
                // Let the message bubble handle clicks and long clicks outside links
                isClickable = false
                isLongClickable = false
                isFocusable = false
                setLineSpacing(0f, 1.1f)
                // Element June: fill each line greedily. The default high-quality/balanced breaking wraps early
                // and leaves a wider gap on the right than the left padding.
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val textView = view as TextView
                    val layout = textView.layout ?: return@addOnLayoutChangeListener
                    val text = textView.text
                    val endsWithCodeBlock = text is android.text.Spanned && text.length > 0 &&
                        text.getSpans(text.length - 1, text.length, CodeBlockSpan::class.java).isNotEmpty()
                    if (endsWithCodeBlock) {
                        // The code block background spans the whole width: put the timestamp on its own row
                        currentContentLayoutChange(ContentAvoidingLayoutData.NotOverlapping)
                    } else {
                        currentMeasure(layout)
                    }
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

private val R_ID_LINK_HANDLER = io.element.android.features.messages.impl.R.id.june_markwon_link_handler

private object MarkwonCache {
    private var key: Pair<Int, Int>? = null
    private var instance: Markwon? = null
    private val parsed = android.util.LruCache<String, android.text.Spanned>(300)

    @Synchronized
    fun markwon(context: android.content.Context, linkColor: Int, codeBackground: Int): Markwon {
        val newKey = linkColor to codeBackground
        instance?.takeIf { key == newKey }?.let { return it }
        parsed.evictAll()
        val built = Markwon.builder(context)
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
                        // Element June: headings only slightly larger than body text (default is 2x..0.67x)
                        .headingTextSizeMultipliers(floatArrayOf(1.25f, 1.15f, 1.08f, 1f, 1f, 1f))
                }

                // Inline code without the non-breaking space padding Markwon adds around it
                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    builder.on(Code::class.java) { visitor, code ->
                        val length = visitor.length()
                        visitor.builder().append(code.literal)
                        visitor.setSpansForNodeOptional(code, length)
                    }
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(LinkResolver { view, link ->
                        @Suppress("UNCHECKED_CAST")
                        (view.getTag(R_ID_LINK_HANDLER) as? (String) -> Unit)?.invoke(link)
                    })
                }
            })
            .build()
        key = newKey
        instance = built
        return built
    }

    @Synchronized
    fun parse(markwon: Markwon, markdown: String): android.text.Spanned {
        parsed.get(markdown)?.let { return it }
        return markwon.toMarkdown(markdown).also { parsed.put(markdown, it) }
    }
}
