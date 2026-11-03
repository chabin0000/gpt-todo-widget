package com.local.gpttodo;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class PageSync {
    public interface Callback {
        void finished(boolean success,String message);
        default void authenticationUrl(String url){}
        default JSONObject approval(String method,JSONObject params)throws Exception{return null;}
    }
    private static final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"page-auto-sync");t.setDaemon(true);return t;});
    private static final AtomicBoolean busy=new AtomicBoolean();
    private PageSync(){}
    public static boolean busy(){return busy.get();}
    public static void run(Context context,boolean allowLogin,Callback callback){
        Context app=context.getApplicationContext();
        if(!busy.compareAndSet(false,true)){if(callback!=null)callback.finished(false,"다른 연결 작업이 진행 중이에요.");return;}
        if(allowLogin)try{app.startForegroundService(new android.content.Intent(app,ConnectionService.class));}
        catch(Exception error){busy.set(false);if(callback!=null)callback.finished(false,"로그인 연결 작업을 시작하지 못했어요. 앱을 연 상태에서 다시 연결해 주세요.");return;}
        executor.execute(()->{
            boolean success=false;String message="";
            CountDownLatch loginFinished=new CountDownLatch(1);AtomicBoolean loginSuccess=new AtomicBoolean();
            try(PhoneRuntime runtime=new PhoneRuntime(app,new ConnectorRpc.Events(){
                @Override public void event(String method,JSONObject params){
                    if("account/login/completed".equals(method)){loginSuccess.set(params!=null&&params.optBoolean("success",false));loginFinished.countDown();}
                }
                @Override public JSONObject request(String method,JSONObject params)throws Exception{
                    if(callback==null)return null;
                    return callback.approval(method,params);
                }
            })){
                String url=TaskStore.url(app);String page=SyncState.pageId(url);
                if(!runtime.signedIn()){
                    SyncState.connected(app,false);
                    if(!allowLogin)throw new IOException("계정 연결이 필요해요. 위젯 앱에서 ChatGPT 계정을 연결해 주세요.");
                    SyncState.message(app,"휴대폰 브라우저에서 ChatGPT 로그인을 완료해 주세요.");
                    JSONObject login=runtime.rpc.call("account/login/start",new JSONObject().put("type","chatgpt").put("useHostedLoginSuccessPage",true),30);
                    String auth=login.getString("authUrl");android.net.Uri authUri=android.net.Uri.parse(auth);
                    String host=authUri.getHost();
                    if(!"https".equals(authUri.getScheme())||host==null||!(host.equals("auth.openai.com")||host.equals("chatgpt.com")))throw new IOException("인증 주소를 확인할 수 없어요.");
                    if(callback!=null)callback.authenticationUrl(auth);
                    if(!loginFinished.await(5,TimeUnit.MINUTES)||!loginSuccess.get())throw new IOException("로그인을 완료하지 못했어요. 앱에서 다시 연결해 주세요.");
                }
                String connection=runtime.rpc.connectionContext(runtime.directory);
                String server=runtime.rpc.discoverPageServer(connection);
                PageSnapshot snapshot=read(runtime,connection,server,page);
                // Import new source items first, even if an older completion later conflicts.
                if(!page.equals(SyncState.pageId(TaskStore.url(app))))throw new IOException("연결할 페이지가 변경되어 이전 응답은 적용하지 않았어요.");
                TaskStore.applySnapshot(app,snapshot);SyncState.recordSnapshot(app,snapshot);TodoWidget.refreshAll(app);
                JSONArray operations=SyncState.queue(app);int conflicts=0;
                for(int i=0;i<operations.length();i++){
                    JSONObject op=operations.getJSONObject(i);
                    if(!"pending".equals(op.optString("state"))||!page.equals(op.optString("page_id"))){conflicts++;continue;}
                    PageSnapshot.Target target;
                    try{target=snapshot.find(op.getString("task_id"),op.optString("block_id",""),op.getString("body"));}
                    catch(IOException changed){SyncState.conflict(app,op.getString("request_id"));conflicts++;continue;}
                    boolean desired=op.getBoolean("completed");
                    if(target.done!=desired){
                        JSONObject receipt=runtime.rpc.pageTool(connection,server,"chatgpt_space.patch_page",snapshot.patch(target,desired));
                        PageSnapshot.requireApplied(receipt);
                        // Read back every write; an uncertain result remains pending and is reconciled next time.
                        snapshot=read(runtime,connection,server,page);
                        PageSnapshot.Target verified=snapshot.find(target.taskId,target.blockId,target.body);
                        if(verified.done!=desired)throw new IOException("원본 완료 상태를 확인하지 못해 반영 대기로 유지합니다.");
                    }
                    SyncState.remove(app,op.getString("request_id"));
                }
                if(!page.equals(SyncState.pageId(TaskStore.url(app))))throw new IOException("연결할 페이지가 변경되어 이전 응답은 적용하지 않았어요.");
                TaskStore.applySnapshot(app,snapshot);
                SyncState.recordSnapshot(app,snapshot);TodoWidget.refreshAll(app);
                message=conflicts>0?"목록을 갱신했어요. 원본 변경으로 확인이 필요한 반영 대기 항목이 있어요.":"원본 목록과 완료 상태를 갱신했어요.";
                success=true;
            }catch(Exception error){message=error.getMessage();if(message==null||message.isEmpty())message="원본 연결에 실패했어요. 기존 목록과 반영 대기 항목을 유지합니다.";}
            finally{
                if(allowLogin)app.stopService(new android.content.Intent(app,ConnectionService.class));
                SyncState.message(app,message);busy.set(false);
                if(callback!=null)callback.finished(success,message);
                if(success&&SyncState.enabled(app)&&SyncState.hasPending(app))SyncJobService.schedule(app,true);
            }
        });
    }
    public static void disconnect(Context context,Callback callback){
        Context app=context.getApplicationContext();SyncState.enabled(app,false);SyncJobService.cancel(app);
        if(!busy.compareAndSet(false,true)){callback.finished(false,"자동 갱신을 중지했어요. 진행 중인 연결이 끝난 뒤 계정 연결을 해제해 주세요.");return;}
        executor.execute(()->{boolean success=false;String message;
            try(PhoneRuntime runtime=new PhoneRuntime(app,null)){
                runtime.rpc.call("account/logout",new JSONObject(),20);SyncState.connected(app,false);success=true;message="이 휴대폰의 계정 연결을 해제했어요. 기존 목록은 유지됩니다.";
            }catch(Exception error){message="자동 갱신은 중지했지만 계정 연결 해제는 확인하지 못했어요. 앱 데이터 삭제로 연결 정보를 제거할 수 있어요.";}
            finally{busy.set(false);}
            SyncState.message(app,message);callback.finished(success,message);
        });
    }
    private static PageSnapshot read(PhoneRuntime r,String connection,String server,String page)throws Exception{
        return new PageSnapshot(r.rpc.pageTool(connection,server,"chatgpt_space.read_page",new JSONObject().put("page_id",page).put("view","editing")),page);
    }
}
