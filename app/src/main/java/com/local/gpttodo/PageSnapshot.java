package com.local.gpttodo;

import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** A fully validated Pages snapshot and exact checkbox targets, in canonical order. */
public final class PageSnapshot {
    private static final Pattern MARKER = Pattern.compile("(?m)^([ \\t]*(?:[-*+]\\s+)?)\\[([ xX])\\]\\s+.+$");
    public final String pageId;
    public final String markdown;
    public final long sequence;
    public final String streamKind;
    public final Map<String, Target> targets;
    public final Map<String, JSONObject> blocks;
    public static final class Target {
        public final String taskId, blockId, body, line;
        public final boolean done;
        Target(String taskId, String blockId, String body, String line, boolean done) {
            this.taskId=taskId;this.blockId=blockId;this.body=body;this.line=line;this.done=done;
        }
        public JSONObject json() throws JSONException {
            return new JSONObject().put("task_id",taskId).put("block_id",blockId).put("body",body);
        }
    }
    public PageSnapshot(JSONObject response, String expectedPage) throws Exception {
        JSONObject content = response.optJSONObject("content");
        if (content == null || !expectedPage.equals(content.optString("page_id"))) throw new IOException("다른 페이지 응답은 적용하지 않습니다.");
        JSONArray sourceBlocks = content.optJSONArray("blocks");
        if (sourceBlocks == null) throw new IOException("페이지 본문이 완전하게 반환되지 않았어요.");
        JSONObject selection = response.optJSONObject("selection");
        if (selection != null && !selection.optBoolean("whole_page_complete", false)) throw new IOException("부분 목록은 전체 목록으로 적용하지 않습니다.");
        pageId = expectedPage;
        JSONObject meta = response.optJSONObject("metadata"); sequence = content.optLong("through_sequence",0);
        streamKind=meta==null?"content":meta.optString("stream_kind","content");
        if(!"content".equals(streamKind))throw new IOException("일반 원본 페이지 본문만 연결할 수 있어요.");
        blocks = new LinkedHashMap<>(); StringBuilder text = new StringBuilder();
        List<String> blockIds = new ArrayList<>(); List<String> lines = new ArrayList<>();
        for (int i=0; i<sourceBlocks.length(); i++) {
            JSONObject block=sourceBlocks.getJSONObject(i);
            if (!"markdown".equals(block.optString("kind"))) continue;
            String body=block.getString("markdown");String id=block.getString("id");
            if (!block.has("hash")) throw new IOException("페이지 블록 검증 정보가 없어요.");
            blocks.put(id, block);if(text.length()>0)text.append("\n\n");text.append(body);
            Matcher matcher=MARKER.matcher(body);
            while(matcher.find()){blockIds.add(id);lines.add(matcher.group());}
        }
        markdown = text.length()==0 ? "## 할 일 목록" : text.toString();
        TaskDocument doc=TaskDocument.parse(markdown);List<TaskDocument.Row> tasks=new ArrayList<>();
        for(TaskDocument.Row row:doc.rows)if(row.isTask())tasks.add(row);
        targets=new LinkedHashMap<>();
        if(tasks.size()!=lines.size())throw new IOException("체크 목록 구조를 안전하게 식별할 수 없어요.");
        for(int i=0;i<tasks.size();i++){
            TaskDocument.Row row=tasks.get(i);
            targets.put(row.id,new Target(row.id,blockIds.get(i),row.markdown,lines.get(i),row.done));
        }
    }
    public Target find(String taskId, String blockId, String expectedBody) throws IOException {
        Target exact=targets.get(taskId);
        if(!blockId.isEmpty() && exact!=null && exact.body.equals(expectedBody) && exact.blockId.equals(blockId))return exact;
        List<Target> same=new ArrayList<>();
        for(Target t:targets.values())if(t.body.equals(expectedBody)&&(blockId.isEmpty()||t.blockId.equals(blockId)))same.add(t);
        if(same.size()!=1)throw new IOException("원본 항목이 변경되었거나 여러 개라 자동 완료를 중단했어요.");
        return same.get(0);
    }
    public JSONObject patch(Target target, boolean completed) throws Exception {
        JSONObject block=blocks.get(target.blockId); String original=block.getString("markdown");
        String old=target.line;
        if(original.indexOf(old)!=original.lastIndexOf(old))throw new IOException("같은 블록에 동일한 항목이 여러 개 있어 자동 수정을 중단했어요.");
        Matcher marker=MARKER.matcher(old);if(!marker.matches())throw new IOException("체크박스를 식별할 수 없어요.");
        int offset=marker.start(2);
        String changed=old.substring(0,offset)+(completed?"x":" ")+old.substring(offset+1);
        return new JSONObject().put("page_id",pageId).put("stream_kind",streamKind).put("changes",new JSONArray().put(new JSONObject()
            .put("block_id",target.blockId).put("expected_hash",block.getString("hash"))
            .put("replacements",new JSONArray().put(new JSONObject().put("old",old).put("new",changed)))));
    }
    public static void requireApplied(JSONObject receipt) throws Exception {
        if("unknown".equals(receipt.optString("commit_status"))||receipt.has("error"))throw new IOException("원본 수정 결과가 확정되지 않았어요. 다음 연결에서 다시 조회합니다.");
        JSONArray operations=receipt.optJSONArray("operation_results");
        if(operations==null||operations.length()!=1)throw new IOException("원본 수정 결과를 확인하지 못했어요. 재조회 후 확인합니다.");
        JSONObject op=operations.getJSONObject(0);
        if(!"applied".equals(op.optString("status","applied")) || op.has("ignored_reason")&&!op.isNull("ignored_reason"))
            throw new IOException("원본 변경 충돌이 발생했어요. 최신 상태를 다시 확인해 주세요.");
    }
}
