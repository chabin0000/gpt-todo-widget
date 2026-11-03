package com.local.gpttodo;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Render the reference's bold text and underlined link labels without independent link clicks. */
public final class MarkdownText {
    private static final Pattern TOKEN = Pattern.compile("\\*\\*(.+?)\\*\\*|\\[([^\\]]+)\\]\\(([^)]+)\\)|`([^`]+)`");
    private MarkdownText() {}
    public static CharSequence render(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        Matcher matcher = TOKEN.matcher(markdown); int last = 0;
        while (matcher.find()) {
            out.append(markdown.substring(last, matcher.start())); int start = out.length();
            if (matcher.group(1) != null) {
                out.append(matcher.group(1)); out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(2) != null) {
                out.append(matcher.group(2)); out.setSpan(new UnderlineSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else out.append(matcher.group(4));
            last = matcher.end();
        }
        out.append(markdown.substring(last));
        return out;
    }
}
