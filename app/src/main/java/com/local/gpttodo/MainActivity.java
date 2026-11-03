package com.local.gpttodo;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private EditText urlField;
    private EditText sourceField;
    private TextView status;
    private TextView syncStatus;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean dark;
    private int textColor;
    private int secondaryColor;
    private volatile boolean foreground;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        textColor = Color.parseColor(dark ? "#F1F1F1" : "#171717");
        secondaryColor = Color.parseColor(dark ? "#BABDBA" : "#5D625F");
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) setResult(RESULT_CANCELED);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(dark ? "#101110" : "#F4F5F4"));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(24), dp(22), dp(30));
        scroll.addView(column);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();

        column.addView(text("할 일 위젯", 27, true, textColor));
        TextView subtitle = text("4 × 2  ·  다크 / 화이트", 13, false, secondaryColor);
        subtitle.setPadding(0, dp(8), 0, dp(22)); column.addView(subtitle);

        LinearLayout card = card(); column.addView(card);
        card.addView(text("페이지 순서 그대로, 홈 화면에서", 16, true, textColor));
        status = text("", 13, false, secondaryColor);
        status.setPadding(0, dp(10), 0, dp(12)); card.addView(status); updateStatus();
        card.addView(text("체크하면 위젯에서 완료 처리됩니다. 본문이나 여백을 누르면 연결한 ChatGPT 페이지가 열립니다.", 13, false, secondaryColor));
        card.addView(button("다크 위젯 추가", v -> pin(DarkWidget.class)));
        card.addView(button("화이트 위젯 추가", v -> pin(LightWidget.class)));

        LinearLayout connectionCard=card();LinearLayout.LayoutParams connectionParams=matchWrap();connectionParams.topMargin=dp(18);column.addView(connectionCard,connectionParams);
        connectionCard.addView(text("원본 자동 갱신",16,true,textColor));
        syncStatus=text("",12,false,secondaryColor);syncStatus.setPadding(0,dp(10),0,dp(10));connectionCard.addView(syncStatus);
        connectionCard.addView(text("최초 한 번 ChatGPT 계정을 연결하면 휴대폰에서 원본 목록을 가져옵니다. 약 15분 주기로 확인하며, 절전 상태에서는 늦어질 수 있어요. 체크한 항목은 원본에 완료 표시를 보냅니다.",12,false,secondaryColor));
        connectionCard.addView(button("ChatGPT 연결 · 자동 갱신 시작",v->connect()));
        connectionCard.addView(button("지금 원본에서 가져오기",v->synchronize(false)));
        connectionCard.addView(button("자동 갱신 중지",v->{SyncState.enabled(this,false);SyncJobService.cancel(this);updateStatus();toast("자동 갱신을 중지했어요. 기존 목록은 유지됩니다.");}));
        connectionCard.addView(button("계정 연결 해제",v->PageSync.disconnect(this,(success,message)->runOnUiThread(()->{updateStatus();toast(message);}))));
        connectionCard.addView(button("반영 대기 요청 확인",v->pendingRequests()));
        TextView nativeNote=text("휴대폰 직접 연결 시험 버전입니다. 런타임·계정 연결 실패 시 기존 목록을 유지하고 오류를 표시합니다. PC와 별도 중계 서버는 사용하지 않습니다.",11,false,secondaryColor);
        nativeNote.setPadding(0,dp(12),0,dp(24));column.addView(nativeNote);

        column.addView(text("열 페이지", 16, true, textColor));
        urlField = new EditText(this);
        urlField.setTextSize(13); urlField.setTextColor(textColor);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setSingleLine(false); urlField.setMaxLines(3);
        urlField.setText(state == null ? TaskStore.url(this) : state.getString("draft_url", TaskStore.url(this)));
        urlField.setContentDescription("여백을 누르면 열 ChatGPT 페이지 주소");
        column.addView(urlField, matchWrap());
        column.addView(button("연결한 ChatGPT 페이지 열기", v -> {
            String url = urlField.getText().toString().trim();
            if (!TaskStore.isAllowedUrl(url)) { toast("ChatGPT 페이지 주소를 확인해 주세요."); return; }
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (android.content.ActivityNotFoundException error) { toast("ChatGPT 앱 또는 브라우저가 필요해요."); }
        }));

        TextView importTitle = text("수동 목록 가져오기", 16, true, textColor);
        importTitle.setPadding(0, dp(26), 0, dp(8)); column.addView(importTitle);
        column.addView(text("자동 갱신을 중지한 상태에서 사용하는 보조 기능입니다. 원본 자동 연결 중에는 아래의 수동 내용 대신 원본을 사용합니다. 형식: - [ ] 할 일", 12, false, secondaryColor));
        sourceField = new EditText(this);
        sourceField.setTextSize(13); sourceField.setTextColor(textColor); sourceField.setMinLines(5); sourceField.setMaxLines(9);
        sourceField.setGravity(android.view.Gravity.TOP);
        sourceField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sourceField.setText(state == null ? TaskStore.source(this) : state.getString("draft_source", TaskStore.source(this)));
        sourceField.setContentDescription("가져올 Markdown 체크 목록");
        sourceField.setPadding(dp(12), dp(12), dp(12), dp(12));
        sourceField.setBackground(background(dark ? "#1C1F1D" : "#FFFFFF", 12));
        sourceField.setVerticalScrollBarEnabled(true);
        sourceField.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(event.getAction() != android.view.MotionEvent.ACTION_UP && event.getAction() != android.view.MotionEvent.ACTION_CANCEL);
            return false;
        });
        LinearLayout.LayoutParams editParams = matchWrap(); editParams.topMargin = dp(12);
        column.addView(sourceField, editParams);
        column.addView(button("클립보드에서 붙여넣기", v -> paste()));
        column.addView(button(widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ? "목록 · 페이지 주소 저장" : "저장하고 위젯 적용", v -> save()));

        TextView doneTitle = text("완료한 항목", 16, true, textColor);
        doneTitle.setPadding(0, dp(28), 0, dp(4)); column.addView(doneTitle);
        column.addView(button("완료한 항목 복원", v -> restore()));
        column.addView(button("완료 반영본 복사", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("할 일 목록", TaskStore.document(this).exportMarkdown(TaskStore.completed(this))));
            toast("완료 상태를 포함한 목록을 복사했어요. ChatGPT에서 수동으로 반영할 수 있어요.");
        }));
        TextView footer = text("별도로 설치하는 개인용 위젯 앱입니다.\n앱 설정에서 본인의 페이지 주소를 연결하세요", 11, false, secondaryColor);
        footer.setPadding(0, dp(26), 0, 0); column.addView(footer);
        if (state == null) acceptShare(getIntent());
        updateStatus();
        if(state==null&&getIntent().getBooleanExtra("connect_requested",false))connect();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("draft_source", sourceField.getText().toString());
        state.putString("draft_url", urlField.getText().toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;if (status != null) updateStatus();
        if(SyncState.enabled(this)&&SyncState.connected(this)){
            SyncJobService.schedule(this,false);
            if(System.currentTimeMillis()-SyncState.lastSuccess(this)>60000&&!PageSync.busy())synchronize(false);
        }
    }
    @Override protected void onPause(){foreground=false;super.onPause();}
    private void acceptShare(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        String text = shared.toString().trim();
        if (TaskStore.isAllowedUrl(text)) {
            urlField.setText(text); toast("페이지 주소를 받았어요. 목록 내용은 별도로 붙여넣어 주세요.");
        } else {
            try {
                if (TaskDocument.parse(text).taskCount() == 0) { toast("체크 목록 내용이 없어요. 페이지의 목록 본문을 복사해 주세요."); return; }
                sourceField.setText(text); toast("목록을 받았어요. 저장하면 위젯에 반영됩니다.");
            } catch (IllegalArgumentException error) { toast(error.getMessage()); }
        }
    }
    private void paste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("복사한 내용이 없어요."); return; }
        CharSequence pasted = clip.getItemAt(0).coerceToText(this);
        if (pasted == null) return;
        String value = pasted.toString().trim();
        if (TaskStore.isAllowedUrl(value)) { urlField.setText(value); toast("페이지 주소를 붙여넣었어요. 목록 본문도 가져와 주세요."); }
        else { sourceField.setText(value); toast("붙여넣었어요. 저장하면 위젯에 반영됩니다."); }
    }
    private void save() {
        try {
            if(SyncState.enabled(this))TaskStore.saveUrl(this,urlField.getText().toString().trim());
            else TaskStore.save(this,sourceField.getText().toString(),urlField.getText().toString().trim());
            TodoWidget.refreshAll(this); updateStatus(); toast("목록과 페이지 주소를 저장했어요.");
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish();
            }
        } catch (IllegalArgumentException | IllegalStateException error) { toast(error.getMessage()); }
    }
    private void updateStatus() {
        int remaining = 0;
        for (TaskDocument.Row row : TaskStore.document(this).visibleRows(TaskStore.completed(this),SyncState.reopened(this))) if (row.isTask()) remaining++;
        status.setText("남은 할 일 " + remaining + "개  ·  기기에 저장된 목록");
        if(syncStatus!=null){
            long time=SyncState.lastSuccess(this);
            String last=time==0?"아직 원본 연결 확인 전":java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new java.util.Date(time));
            syncStatus.setText((SyncState.enabled(this)?"자동 갱신 켜짐":"자동 갱신 꺼짐")+"  ·  반영 대기 "+SyncState.pending(this)+"개\n마지막 가져오기: "+last+"\n"+SyncState.message(this));
        }
    }
    private void pin(Class<?> provider) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager.isRequestPinAppWidgetSupported()) manager.requestPinAppWidget(new ComponentName(this, provider), null, null);
        else toast("홈 화면을 길게 누른 뒤 위젯 → GPT 할 일 위젯에서 추가해 주세요.");
    }
    private void restore() {
        Set<String> done = TaskStore.completed(this);
        List<TaskDocument.Row> rows = new ArrayList<>();
        for (TaskDocument.Row row : TaskStore.document(this).rows) if (row.isTask() && ((row.done && SyncState.enabled(this)) || done.contains(row.id))) rows.add(row);
        if (rows.isEmpty()) { toast("이 기기에서 완료한 항목이 없어요."); return; }
        CharSequence[] labels = new CharSequence[rows.size()];
        for (int i = 0; i < rows.size(); i++) labels[i] = MarkdownText.render(rows.get(i).markdown);
        Set<String> selected = new HashSet<>();
        new AlertDialog.Builder(this).setTitle("복원할 항목 선택")
            .setMultiChoiceItems(labels, null, (dialog, which, checked) -> {
                if (checked) selected.add(rows.get(which).id); else selected.remove(rows.get(which).id);
            }).setPositiveButton("복원", (dialog, which) -> {
                try{for(String id:selected)if(SyncState.enabled(this)||SyncState.hasRequest(this,id))SyncState.enqueue(this,id,false);}
                catch(Exception error){toast(error.getMessage());return;}
                if (!TaskStore.restore(this, selected)) { toast("저장하지 못했어요. 다시 시도해 주세요."); return; }
                TodoWidget.refreshAll(this);SyncJobService.schedule(this,true);updateStatus();toast("선택한 항목을 원래 위치에 복원했어요.");
            }).setNegativeButton("취소", null).show();
    }
    private void connect(){
        try{SyncState.pageId(urlField.getText().toString().trim());TaskStore.saveUrl(this,urlField.getText().toString().trim());}
        catch(Exception error){toast(error.getMessage());return;}
        SyncState.enabled(this,true);synchronize(true);
    }
    private void synchronize(boolean login){
        if(!login&&!SyncState.enabled(this)){toast("먼저 ChatGPT 연결을 시작해 주세요.");return;}
        SyncState.message(this,"원본 연결 확인 중…");updateStatus();
        PageSync.run(this,login,new PageSync.Callback(){
            @Override public org.json.JSONObject approval(String method,org.json.JSONObject params)throws Exception{
                if(!foreground||isFinishing()||params==null)return null;
                if(!method.equals("tool/requestUserInput"))return null;
                org.json.JSONArray questions=params.optJSONArray("questions");
                if(questions==null||questions.length()==0||questions.length()>3)return null;
                org.json.JSONObject answers=new org.json.JSONObject();
                for(int q=0;q<questions.length();q++){
                    org.json.JSONObject question=questions.getJSONObject(q);
                    org.json.JSONArray options=question.optJSONArray("options");
                    if(options==null||options.length()==0||question.optBoolean("isSecret",false))return null;
                    java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.atomic.AtomicReference<String> selected=new java.util.concurrent.atomic.AtomicReference<>();
                    String[] labels=new String[options.length()];
                    for(int i=0;i<labels.length;i++)labels[i]=options.getJSONObject(i).getString("label");
                    runOnUiThread(()->{
                        if(!foreground||isFinishing()){latch.countDown();return;}
                        new AlertDialog.Builder(MainActivity.this).setTitle(question.optString("question","원본 연결 도구의 승인 요청"))
                            .setItems(labels,(dialog,index)->{selected.set(labels[index]);latch.countDown();})
                            .setOnCancelListener(dialog->latch.countDown()).show();
                    });
                    if(!latch.await(45,java.util.concurrent.TimeUnit.SECONDS)||selected.get()==null)return null;
                    answers.put(question.getString("id"),new org.json.JSONObject().put("answers",new org.json.JSONArray().put(selected.get())));
                }
                return new org.json.JSONObject().put("answers",answers);
            }
            @Override public void authenticationUrl(String url){runOnUiThread(()->{
                if(isFinishing())return;
                updateStatus();
                try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}
                catch(android.content.ActivityNotFoundException error){toast("로그인할 웹 브라우저가 필요해요.");}
            });}
            @Override public void finished(boolean success,String message){runOnUiThread(()->{
                if(isFinishing())return;
                if(success){SyncJobService.schedule(MainActivity.this,false);sourceField.setText(TaskStore.source(MainActivity.this));}
                else if(!SyncState.connected(MainActivity.this)){SyncState.enabled(MainActivity.this,false);SyncJobService.cancel(MainActivity.this);}
                updateStatus();toast(message);
            });}
        });
    }
    private void pendingRequests(){
        org.json.JSONArray requests=SyncState.queue(this);
        if(requests.length()==0){toast("반영 대기 요청이 없어요.");return;}
        String[] labels=new String[requests.length()];
        for(int i=0;i<labels.length;i++){org.json.JSONObject op=requests.optJSONObject(i);labels[i]=(op.optBoolean("completed")?"완료: ":"복원: ")+MarkdownText.render(op.optString("body"));}
        new AlertDialog.Builder(this).setTitle("원본 반영 대기 · 선택하면 취소")
            .setItems(labels,(dialog,index)->{
                if(PageSync.busy()){toast("반영 중인 요청은 작업이 끝난 뒤 확인해 주세요.");return;}
                org.json.JSONObject op=requests.optJSONObject(index);
                SyncState.remove(this,op.optString("request_id"));
                java.util.Set<String> id=new java.util.HashSet<>();id.add(op.optString("task_id"));TaskStore.restore(this,id);
                TodoWidget.refreshAll(this);updateStatus();toast("선택한 원본 반영 요청을 취소했어요.");
            }).setNegativeButton("닫기",null).show();
    }
    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f); if (bold) view.setTypeface(null, Typeface.BOLD); return view;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setTextSize(13); button.setAllCaps(false);
        button.setOnClickListener(listener); button.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(8); button.setLayoutParams(params); return button;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(background(dark ? "#1C1F1D" : "#FFFFFF", 20)); return card;
    }
    private GradientDrawable background(String color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Color.parseColor(color)); shape.setCornerRadius(dp(radius)); return shape;
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
