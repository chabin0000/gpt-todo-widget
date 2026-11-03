package com.local.gpttodo;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Line-delimited app-server client. This class never starts an AI/model turn. */
public final class ConnectorRpc implements AutoCloseable {
    public interface Events {
        void event(String method, JSONObject params);
        default JSONObject request(String method,JSONObject params)throws Exception{return null;}
    }
    private final Process child;
    private final BufferedWriter input;
    private final AtomicInteger serial = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JSONObject>> calls = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile String failure = "연결 프로세스가 종료됐어요.";
    private volatile boolean approvalNeeded;
    private final Events events;

    public ConnectorRpc(ProcessBuilder builder, Events events) throws Exception {
        this.events = events;
        child = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        Thread stdout = new Thread(() -> read(child.getInputStream()), "page-connector-rpc");
        stdout.setDaemon(true); stdout.start();
        // Drain diagnostics without persisting credentials, tool payloads or auth URLs.
        Thread stderr = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Permission denied")) failure = "휴대폰에서 연결 런타임 실행이 차단됐어요.";
                    else if (line.contains("No such file")) failure = "연결 런타임의 호환성 또는 파일을 확인해 주세요.";
                }
            } catch (IOException ignored) {}
        }, "page-connector-diagnostics");
        stderr.setDaemon(true); stderr.start();
    }
    private void read(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 8_000_000) throw new IOException("응답 크기 제한을 초과했어요.");
                JSONObject message;
                try { message = new JSONObject(line); } catch (Exception ignored) { continue; }
                if (message.has("method")) {
                    if (message.has("id")) {
                        // A foreground app may show the platform's request to the user. Never auto-approve it.
                        JSONObject answer=null;
                        try{if(events!=null)answer=events.request(message.getString("method"),message.optJSONObject("params"));}catch(Exception ignored){}
                        if(answer!=null)send(new JSONObject().put("id",message.get("id")).put("result",answer));
                        else {
                            approvalNeeded=true;
                            send(new JSONObject().put("id", message.get("id")).put("error", new JSONObject()
                                .put("code", -32000).put("message", "Open the app to review connector authorization.")));
                        }
                    } else if (events != null) events.event(message.getString("method"), message.optJSONObject("params"));
                } else if (message.has("id")) {
                    CompletableFuture<JSONObject> future = calls.remove(message.optInt("id", -1));
                    if (future != null) {
                        if (message.has("error")) future.completeExceptionally(new IOException(approvalNeeded?"원본 변경 승인이 필요해요. 위젯 앱에서 지금 원본에서 가져오기를 눌러 확인해 주세요.":"연결 요청이 거절됐어요. 계정과 페이지 권한을 확인해 주세요."));
                        else future.complete(message.optJSONObject("result") == null ? new JSONObject() : message.getJSONObject("result"));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if(child.waitFor(1,TimeUnit.SECONDS)&&child.exitValue()!=0)
                    failure="기기 연결 모듈이 종료됐어요 (코드 "+child.exitValue()+"). 휴대폰 호환성을 확인해야 합니다. 원본 반영 요청은 보관됩니다.";
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            closed = true;
            for (CompletableFuture<JSONObject> f : calls.values()) f.completeExceptionally(new IOException(failure));
            calls.clear();
        }
    }
    private synchronized void send(JSONObject value) throws IOException {
        input.write(value.toString()); input.newLine(); input.flush();
    }
    public JSONObject call(String method, JSONObject params, int seconds) throws Exception {
        if (closed) throw new IOException(failure);
        int id = serial.incrementAndGet(); CompletableFuture<JSONObject> future = new CompletableFuture<>(); calls.put(id, future);
        try {
            send(new JSONObject().put("id", id).put("method", method).put("params", params));
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            throw error.getCause() instanceof Exception ? (Exception) error.getCause() : error;
        } catch (TimeoutException error) { throw new IOException("응답을 기다리는 시간이 초과됐어요. 기존 목록은 유지됩니다."); }
        finally { calls.remove(id); }
    }
    public void initialize() throws Exception {
        call("initialize", new JSONObject().put("clientInfo", new JSONObject().put("name", "gpt_todo_widget").put("version", "2.0"))
            .put("capabilities", new JSONObject().put("experimentalApi", true)), 30);
        send(new JSONObject().put("method", "initialized"));
    }
    public String connectionContext(String workingDirectory) throws Exception {
        JSONObject result = call("thread/start", new JSONObject().put("ephemeral", true).put("cwd", workingDirectory)
            .put("config", new JSONObject().put("features.apps", true)), 45);
        return result.getJSONObject("thread").getString("id");
    }
    public String discoverPageServer(String contextId) throws Exception {
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JSONObject args = new JSONObject().put("threadId", contextId).put("detail", "toolsAndAuthOnly").put("limit", 100);
            if (cursor != null) args.put("cursor", cursor);
            JSONObject result = call("mcpServerStatus/list", args, 60);
            JSONArray data = result.optJSONArray("data");
            if (data != null) for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i); JSONObject tools = item.optJSONObject("tools");
                if (tools != null && tools.has("chatgpt_space.read_page")) return item.getString("name");
            }
            cursor = result.optString("nextCursor", ""); if (cursor.isEmpty()) break;
        }
        throw new IOException("이 계정의 Pages 연결 도구를 찾지 못했어요. ChatGPT에서 페이지 접근 권한을 확인해 주세요.");
    }
    public JSONObject pageTool(String contextId, String server, String action, JSONObject args) throws Exception {
        if (!action.equals("chatgpt_space.read_page") && !action.equals("chatgpt_space.patch_page")) throw new SecurityException("Unsupported action");
        JSONObject result = call("mcpServer/tool/call", new JSONObject().put("threadId", contextId).put("server", server)
            .put("tool", action).put("arguments", args), 60);
        JSONObject data = result.optJSONObject("structuredContent");
        if (result.optBoolean("isError", false) || data == null || data.has("error")) throw new IOException(approvalNeeded?"원본 변경 승인이 필요해요. 위젯 앱에서 지금 원본에서 가져오기를 눌러 확인해 주세요.":"페이지 읽기 또는 수정 요청이 실패했어요. 원본 반영을 완료로 처리하지 않습니다.");
        return data;
    }
    @Override public void close() {
        closed = true;
        try { input.close(); } catch (IOException ignored) {}
        child.destroy();
        for (CompletableFuture<JSONObject> f : calls.values()) f.completeExceptionally(new IOException("연결이 종료됐어요."));
        calls.clear();
    }
}
