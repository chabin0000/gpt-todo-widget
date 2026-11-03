package com.local.gpttodo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** NoDisplay activity finishes in onCreate, so checking never opens a visible app screen. */
public final class WidgetActionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("complete".equals(getIntent().getStringExtra("operation"))) {
            try {
                String id=getIntent().getStringExtra("task_id");
                SyncState.enqueue(this,id,true);
                if(!TaskStore.complete(this,id))throw new IllegalStateException("기기의 완료 상태를 저장하지 못했어요.");
                TodoWidget.refreshAll(this);
                SyncState.enabled(this,true);SyncJobService.schedule(this,true);
                if(!SyncState.connected(this))startActivity(new Intent(this,MainActivity.class)
                    .putExtra("connect_requested",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else Toast.makeText(this,"완료 표시를 원본에 반영 대기 중이에요.",Toast.LENGTH_SHORT).show();
            }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
        } else openSource(this);
        finish();
    }
    public static void openSource(Context context) {
        String url = TaskStore.url(context);
        if (!TaskStore.isAllowedUrl(url)) url = TaskStore.DEFAULT_URL;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(new Intent(intent).setPackage("com.openai.chatgpt")); }
        catch (ActivityNotFoundException noApp) {
            try { context.startActivity(intent); }
            catch (ActivityNotFoundException noBrowser) { Toast.makeText(context, "ChatGPT 앱 또는 웹 브라우저를 설치해 주세요.", Toast.LENGTH_LONG).show(); }
        }
    }
}
