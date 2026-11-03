package com.local.gpttodo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.widget.RemoteViews;
import java.util.List;

public abstract class TodoWidget extends AppWidgetProvider {
    protected abstract boolean isDark();
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id, isDark());
        SyncJobService.schedule(context,false);
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, id, isDark());
    }
    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, DarkWidget.class))) update(context, manager, id, true);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, LightWidget.class))) update(context, manager, id, false);
    }
    private static void update(Context context, AppWidgetManager manager, int id, boolean dark) {
        TaskDocument doc = TaskStore.document(context);
        List<TaskDocument.Row> rows = doc.visibleRows(TaskStore.completed(context),SyncState.reopened(context));
        int foreground = Color.parseColor(dark ? "#E6E6E6" : "#252525");
        int heading = Color.parseColor(dark ? "#ECECEC" : "#171717");
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        views.setInt(R.id.widget_root, "setBackgroundResource", dark ? R.drawable.widget_dark_background : R.drawable.widget_light_background);
        views.setTextViewText(R.id.widget_title, MarkdownText.render(doc.title));
        views.setTextColor(R.id.widget_title, heading);
        views.setTextColor(R.id.empty_view, foreground);
        if(!TaskStore.currentSource(context))views.setTextViewText(R.id.empty_view,"새 원본 페이지를 연결해 주세요.\n여기를 누르면 페이지를 엽니다.");

        // An activity template handles BOTH actions. A broadcast/service that then opens an
        // activity would be a prohibited notification/widget trampoline on modern Android.
        Intent templateIntent = new Intent(context, WidgetActionActivity.class);
        templateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        templateIntent.setAction("com.local.gpttodo.COLLECTION." + id);
        PendingIntent template = PendingIntent.getActivity(context, id, templateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.task_list, template);
        Intent openIntent = new Intent(context, WidgetActionActivity.class).putExtra("operation", "open");
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        openIntent.setAction("com.local.gpttodo.OPEN." + id);
        PendingIntent open = PendingIntent.getActivity(context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_title, open);
        views.setOnClickPendingIntent(R.id.list_area, open);
        views.setOnClickPendingIntent(R.id.empty_view, open);
        views.setEmptyView(R.id.task_list, R.id.empty_view);

        RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true).setViewTypeCount(3);
        for (TaskDocument.Row row : rows) {
            RemoteViews item;
            Intent fillOpen = new Intent().putExtra("operation", "open");
            if (row.isTask()) {
                item = new RemoteViews(context.getPackageName(), R.layout.widget_task);
                item.setTextViewText(R.id.task_text, MarkdownText.render(row.markdown));
                item.setTextColor(R.id.task_text, foreground);
                item.setImageViewResource(R.id.check_icon, dark ? R.drawable.check_dark : R.drawable.check_light);
                item.setContentDescription(R.id.check_target, "완료 처리: " + MarkdownText.render(row.markdown));
                item.setOnClickFillInIntent(R.id.check_target, new Intent().putExtra("operation", "complete").putExtra("task_id", row.id));
                item.setOnClickFillInIntent(R.id.task_text, fillOpen);
                item.setOnClickFillInIntent(R.id.task_row, fillOpen);
            } else {
                item = new RemoteViews(context.getPackageName(), R.layout.widget_heading);
                item.setTextViewText(R.id.heading_text, MarkdownText.render(row.markdown));
                item.setTextColor(R.id.heading_text, heading);
                item.setOnClickFillInIntent(R.id.heading_text, fillOpen);
            }
            items.addItem(row.stableId(), item);
        }
        if (!rows.isEmpty()) {
            // AdapterView consumes taps below its last real item. A clickable blank footer
            // covers that area too; the empty-list state already has its own open action.
            RemoteViews blank = new RemoteViews(context.getPackageName(), R.layout.widget_blank);
            blank.setInt(R.id.blank_space, "setHeight", blankHeight(context, manager.getAppWidgetOptions(id), rows));
            blank.setOnClickFillInIntent(R.id.blank_space, new Intent().putExtra("operation", "open"));
            items.addItem(Long.MAX_VALUE, blank);
        }
        views.setRemoteAdapter(R.id.task_list, items.build());
        manager.updateAppWidget(id, views);
    }

    private static int blankHeight(Context context, Bundle options, List<TaskDocument.Row> rows) {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        boolean portrait = context.getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int width = options.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320);
        int height = options.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160);
        int available = Math.max(1, Math.round((width - 64) * dm.density));
        TextPaint paint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, dm));
        float consumed = 0;
        for (TaskDocument.Row row : rows) {
            if (row.isTask()) {
                StaticLayout layout = StaticLayout.Builder.obtain(MarkdownText.render(row.markdown), 0,
                    MarkdownText.render(row.markdown).length(), paint, available)
                    .setIncludePad(false).setMaxLines(3).setLineSpacing(2 * dm.density, 1).build();
                consumed += Math.max(40 * dm.density, layout.getHeight() + 16 * dm.density);
            } else consumed += 30 * dm.density;
        }
        // The small overfill absorbs launcher padding/font differences and remains scrollable.
        return Math.max(Math.round(24 * dm.density), Math.round((height - 32) * dm.density - consumed));
    }
}
