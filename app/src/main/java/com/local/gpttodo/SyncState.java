package com.local.gpttodo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.util.*;

public final class SyncState {
    private SyncState(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("page_sync_v2",Context.MODE_PRIVATE);}
    public static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false);}
    public static void enabled(Context c,boolean value){prefs(c).edit().putBoolean("enabled",value).commit();}
    public static boolean connected(Context c){try{return prefs(c).getBoolean("connected",false)&&pageId(TaskStore.url(c)).equals(prefs(c).getString("page_id",""));}catch(Exception e){return false;}}
    public static void connected(Context c,boolean value){prefs(c).edit().putBoolean("connected",value).commit();}
    public static String message(Context c){return prefs(c).getString("message","원본 자동 갱신을 시작하려면 ChatGPT 계정을 연결해 주세요.");}
    public static void message(Context c,String text){prefs(c).edit().putString("message",text).commit();}
    public static long lastSuccess(Context c){return TaskStore.currentSource(c)?prefs(c).getLong("last_success",0):0;}
    public static String pageId(String url){
        if(!TaskStore.isAllowedUrl(url))throw new IllegalArgumentException("ChatGPT 페이지 주소를 확인해 주세요.");
        Uri u=Uri.parse(url);String id=u.getLastPathSegment();
        if(!"chatgpt.com".equalsIgnoreCase(u.getHost())||!u.getPath().startsWith("/space/")||id==null||!id.matches("page_[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException("자동 갱신에는 /space/page_ 로 시작하는 원본 페이지 주소가 필요해요.");
        return id;
    }
    public static synchronized JSONArray queue(Context c){try{return new JSONArray(prefs(c).getString("queue","[]"));}catch(JSONException e){return new JSONArray();}}
    public static synchronized int pending(Context c){return queue(c).length();}
    public static synchronized boolean hasPending(Context c){
        String page;try{page=pageId(TaskStore.url(c));}catch(Exception e){return false;}
        JSONArray q=queue(c);for(int i=0;i<q.length();i++){JSONObject op=q.optJSONObject(i);if(op!=null&&page.equals(op.optString("page_id"))&&"pending".equals(op.optString("state")))return true;}return false;
    }
    public static synchronized boolean hasRequest(Context c,String taskId){
        String page;try{page=pageId(TaskStore.url(c));}catch(Exception e){return false;}
        JSONArray q=queue(c);for(int i=0;i<q.length();i++){JSONObject op=q.optJSONObject(i);if(op!=null&&page.equals(op.optString("page_id"))&&taskId.equals(op.optString("task_id")))return true;}return false;
    }
    public static synchronized Set<String> pendingCompleted(Context c,String page){
        Set<String> result=new HashSet<>();JSONArray q=queue(c);
        for(int i=0;i<q.length();i++){JSONObject op=q.optJSONObject(i);if(op!=null&&page.equals(op.optString("page_id"))&&op.optBoolean("completed",true))result.add(op.optString("task_id"));}
        return result;
    }
    public static synchronized Set<String> reopened(Context c){
        Set<String> result=new HashSet<>();JSONArray q=queue(c);
        String page;try{page=pageId(TaskStore.url(c));}catch(Exception e){return result;}
        for(int i=0;i<q.length();i++){JSONObject op=q.optJSONObject(i);if(op!=null&&page.equals(op.optString("page_id"))&&!op.optBoolean("completed",true))result.add(op.optString("task_id"));}
        return result;
    }
    public static synchronized void enqueue(Context c,String taskId,boolean completed) throws Exception {
        if(!TaskStore.currentSource(c))throw new IllegalStateException("새 원본 페이지의 목록을 먼저 가져와 주세요.");
        String page=pageId(TaskStore.url(c));TaskDocument.Row selected=null;
        for(TaskDocument.Row row:TaskStore.document(c).rows)if(row.isTask()&&row.id.equals(taskId))selected=row;
        if(selected==null)throw new IllegalArgumentException("이 항목은 현재 목록에 없어요.");
        JSONObject ref=new JSONObject(prefs(c).getString("targets","{}"));JSONObject known=ref.optJSONObject(taskId);
        String block="";
        if(page.equals(prefs(c).getString("page_id",""))&&known!=null)block=known.optString("block_id","");
        JSONObject operation=new JSONObject().put("request_id",UUID.randomUUID().toString()).put("task_id",taskId)
            .put("page_id",page).put("block_id",block).put("body",selected.markdown).put("completed",completed).put("state","pending");
        JSONArray next=new JSONArray();JSONArray old=queue(c);
        for(int i=0;i<old.length();i++){JSONObject op=old.getJSONObject(i);if(!taskId.equals(op.optString("task_id"))||!page.equals(op.optString("page_id")))next.put(op);}
        next.put(operation);
        if(!prefs(c).edit().putString("queue",next.toString()).commit())throw new IllegalStateException("반영 대기 상태를 저장하지 못했어요.");
    }
    public static synchronized void remove(Context c,String requestId){
        JSONArray next=new JSONArray();JSONArray old=queue(c);
        for(int i=0;i<old.length();i++){JSONObject op=old.optJSONObject(i);if(op!=null&&!requestId.equals(op.optString("request_id")))next.put(op);}
        prefs(c).edit().putString("queue",next.toString()).commit();
    }
    public static synchronized void conflict(Context c,String requestId){
        JSONArray old=queue(c);
        for(int i=0;i<old.length();i++){JSONObject op=old.optJSONObject(i);if(op!=null&&requestId.equals(op.optString("request_id")))try{op.put("state","review");}catch(JSONException ignored){}}
        prefs(c).edit().putString("queue",old.toString()).commit();
    }
    public static synchronized void recordSnapshot(Context c,PageSnapshot snapshot)throws Exception{
        JSONObject refs=new JSONObject();for(PageSnapshot.Target t:snapshot.targets.values())refs.put(t.taskId,t.json());
        if(!prefs(c).edit().putString("targets",refs.toString()).putString("page_id",snapshot.pageId).putLong("last_success",System.currentTimeMillis())
            .putBoolean("connected",true).putString("message","원본 목록을 가져왔어요.").commit())throw new IllegalStateException("동기화 상태 저장에 실패했어요.");
    }
}
