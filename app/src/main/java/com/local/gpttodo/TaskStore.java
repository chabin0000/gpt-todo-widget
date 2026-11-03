package com.local.gpttodo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class TaskStore {
    public static final String DEFAULT_URL = "https://chatgpt.com/";
    private TaskStore() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("todo_widget_v1", Context.MODE_PRIVATE);
    }
    public static String source(Context context) {
        String saved = prefs(context).getString("source", null);
        if (saved != null) return saved;
        try (InputStream in = context.getAssets().open("initial.md"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { return "## 오늘\n\n### 최우선 과제"; }
    }
    public static String sourceUrl(Context context){return prefs(context).getString("source_url",DEFAULT_URL);}
    public static boolean currentSource(Context context){return sourceUrl(context).equals(url(context));}
    public static TaskDocument document(Context context) { return TaskDocument.parse(currentSource(context)?source(context):"## 원본 연결 필요"); }
    public static synchronized void saveUrl(Context context,String url){
        if(!isAllowedUrl(url))throw new IllegalArgumentException("ChatGPT 페이지 주소를 확인해 주세요.");
        if(!prefs(context).edit().putString("url",url).commit())throw new IllegalStateException("페이지 주소를 저장하지 못했어요.");
    }
    public static synchronized void applySnapshot(Context context, PageSnapshot snapshot) throws Exception {
        TaskDocument doc=TaskDocument.parse(snapshot.markdown);Set<String> valid=new HashSet<>();
        for(TaskDocument.Row row:doc.rows)if(row.isTask())valid.add(row.id);
        // The Page owns confirmed completion state. Only unsent local requests may override it.
        Set<String> done=SyncState.pendingCompleted(context,snapshot.pageId);done.retainAll(valid);
        if(!prefs(context).edit().putString("source",snapshot.markdown).putString("source_url",url(context)).putStringSet("completed",done).commit())
            throw new IllegalStateException("원본 목록을 기기에 저장하지 못했어요.");
    }
    public static Set<String> completed(Context context) {
        return new HashSet<>(prefs(context).getStringSet("completed", new HashSet<>()));
    }
    public static String url(Context context) { return prefs(context).getString("url", DEFAULT_URL); }
    public static boolean isAllowedUrl(String text) {
        Uri uri = Uri.parse(text);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null &&
            (uri.getPort() == -1 || uri.getPort() == 443) &&
            ("chatgpt.com".equalsIgnoreCase(uri.getHost()) || "chat.openai.com".equalsIgnoreCase(uri.getHost()));
    }
    public static synchronized void save(Context context, String source, String url) {
        TaskDocument doc = TaskDocument.parse(source);
        if (doc.taskCount() == 0) throw new IllegalArgumentException("- [ ] 할 일 형식의 체크 목록을 붙여넣어 주세요.");
        if (!isAllowedUrl(url)) throw new IllegalArgumentException("https://chatgpt.com/ 으로 시작하는 페이지 또는 대화 주소를 입력해 주세요.");
        Set<String> validIds = new HashSet<>();
        for (TaskDocument.Row row : doc.rows) if (row.isTask()) validIds.add(row.id);
        Set<String> done = sourceUrl(context).equals(url)?completed(context):new HashSet<>(); done.retainAll(validIds);
        if (!prefs(context).edit().putString("source", source).putString("url", url)
                .putString("source_url",url).putStringSet("completed", done).commit()) throw new IllegalStateException("목록 저장에 실패했어요. 다시 시도해 주세요.");
    }
    public static synchronized boolean complete(Context context, String id) {
        boolean exists = false;
        for (TaskDocument.Row row : document(context).rows) if (row.isTask() && row.id.equals(id)) exists = true;
        if (!exists) return false;
        Set<String> done = completed(context); done.add(id);
        return prefs(context).edit().putStringSet("completed", done).commit();
    }
    public static synchronized boolean restore(Context context, Set<String> ids) {
        Set<String> done = completed(context); done.removeAll(ids);
        return prefs(context).edit().putStringSet("completed", done).commit();
    }
}
