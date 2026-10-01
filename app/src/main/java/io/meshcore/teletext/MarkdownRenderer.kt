package io.meshcore.teletext

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View

object MarkdownRenderer {
    private val inline = Regex("\\[([^]]+)]\\((?:page:)?([0-9]{3})\\)|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|`([^`]+)`")

    fun render(markdown: String, onPage: (Int) -> Unit): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        var codeBlock = false
        for (line in markdown.split('\n')) {
            if (line.trimStart().startsWith("```")) {
                codeBlock = !codeBlock
                continue
            }
            val start = result.length
            var text = line
            val heading = Regex("^(#{1,6})\\s+").find(text)
            if (heading != null) text = text.substring(heading.value.length)
            if (text.startsWith("- ") || text.startsWith("* ")) text = "• " + text.substring(2)
            if (codeBlock) {
                result.append(text)
                result.setSpan(TypefaceSpan("monospace"), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                appendInline(result, text, onPage)
            }
            if (heading != null) {
                result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val size = 1.5f - (heading.groupValues[1].length - 1) * 0.1f
                result.setSpan(RelativeSizeSpan(size), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            result.append('\n')
        }
        return result
    }

    private fun appendInline(out: SpannableStringBuilder, line: String, onPage: (Int) -> Unit) {
        var cursor = 0
        for (match in inline.findAll(line)) {
            out.append(line.substring(cursor, match.range.first))
            val start = out.length
            when {
                match.groupValues[1].isNotEmpty() -> {
                    val page = match.groupValues[2].toInt()
                    out.append(match.groupValues[1])
                    if (page in 100..999) {
                        out.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) = onPage(page)
                        }, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                match.groupValues[3].isNotEmpty() -> {
                    out.append(match.groupValues[3])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                match.groupValues[4].isNotEmpty() -> {
                    out.append(match.groupValues[4])
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(match.groupValues[5])
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            cursor = match.range.last + 1
        }
        out.append(line.substring(cursor))
    }
}
