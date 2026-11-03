package com.local.gpttodo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java model. Source order is authoritative; there is deliberately no sorting. */
public final class TaskDocument {
    public static final int MAX_SOURCE_LENGTH = 100_000;
    public static final int MAX_TASKS = 200;
    private static final Pattern TASK = Pattern.compile("^\\s*(?:[-*+]\\s+)?\\[([ xX])\\]\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.+)$");
    public final String title;
    public final List<Row> rows;

    public static final class Row {
        public final String id;
        public final String markdown;
        public final int level;
        public final boolean done;
        Row(String id, String markdown, int level, boolean done) {
            this.id = id; this.markdown = markdown; this.level = level; this.done = done;
        }
        public boolean isTask() { return level == 0; }
        public long stableId() { return Long.parseUnsignedLong(id.substring(0, 16), 16); }
    }

    private TaskDocument(String title, List<Row> rows) {
        this.title = title;
        this.rows = Collections.unmodifiableList(rows);
    }

    public static TaskDocument parse(String source) {
        if (source == null || source.length() > MAX_SOURCE_LENGTH)
            throw new IllegalArgumentException("목록은 100,000자 이내로 가져와 주세요.");
        String title = "할 일 목록";
        boolean gotTitle = false;
        boolean gotTask = false;
        List<Row> result = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        StringBuilder pendingText = null;
        boolean pendingDone = false;
        int taskCount = 0;
        for (String raw : source.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.trim();
            Matcher task = TASK.matcher(line);
            Matcher heading = HEADING.matcher(line);
            if (task.matches() || heading.matches()) {
                if (pendingText != null) {
                    result.add(taskRow(pendingText.toString(), pendingDone, occurrences));
                    pendingText = null;
                }
                if (task.matches()) {
                    if (++taskCount > MAX_TASKS) throw new IllegalArgumentException("할 일은 최대 200개까지 가져올 수 있어요.");
                    pendingText = new StringBuilder(task.group(2));
                    pendingDone = !task.group(1).equals(" ");
                    gotTask = true;
                } else {
                    int level = heading.group(1).length();
                    String text = heading.group(2);
                    if (!gotTitle && !gotTask && level <= 2) {
                        title = text; gotTitle = true;
                    } else {
                        result.add(new Row(identity("heading:" + level + ":" + text, occurrences), text, level, false));
                    }
                }
            } else if (pendingText != null && !line.isEmpty() && !line.equals("---") && !line.startsWith("```")) {
                pendingText.append(' ').append(line);
            }
        }
        if (pendingText != null) result.add(taskRow(pendingText.toString(), pendingDone, occurrences));
        return new TaskDocument(title, result);
    }

    private static Row taskRow(String markdown, boolean done, Map<String, Integer> occurrences) {
        if (markdown.length() > 12_000) throw new IllegalArgumentException("한 항목의 내용은 12,000자 이내로 줄여 주세요.");
        return new Row(identity("task:" + markdown, occurrences), markdown, 0, done);
    }

    private static String identity(String value, Map<String, Integer> occurrences) {
        int occurrence = occurrences.containsKey(value) ? occurrences.get(value) : 0;
        occurrences.put(value, occurrence + 1);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((value + "\n" + occurrence).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public int taskCount() {
        int count = 0;
        for (Row row : rows) if (row.isTask()) count++;
        return count;
    }

    /** Hide completed tasks and headings whose entire section is now empty. */
    public List<Row> visibleRows(Set<String> completed) {
        return visibleRows(completed,Collections.emptySet());
    }
    public List<Row> visibleRows(Set<String> completed, Set<String> reopened) {
        List<Row> visible = new ArrayList<>();
        List<Row> headings = new ArrayList<>();
        for (Row row : rows) {
            if (!row.isTask()) {
                while (!headings.isEmpty() && headings.get(headings.size() - 1).level >= row.level)
                    headings.remove(headings.size() - 1);
                headings.add(row);
            } else if ((!row.done || reopened.contains(row.id)) && !completed.contains(row.id)) {
                visible.addAll(headings); headings.clear(); visible.add(row);
            }
        }
        return visible;
    }

    public String exportMarkdown(Set<String> completed) {
        StringBuilder out = new StringBuilder("## ").append(title).append("\n\n");
        for (Row row : rows) {
            if (row.isTask()) out.append(row.done || completed.contains(row.id) ? "- [x] " : "- [ ] ");
            else for (int i = 0; i < row.level; i++) out.append('#');
            if (!row.isTask()) out.append(' ');
            out.append(row.markdown).append("\n\n");
        }
        return out.toString().trim();
    }
}
