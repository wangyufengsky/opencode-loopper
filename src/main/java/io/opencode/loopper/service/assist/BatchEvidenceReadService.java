package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.PageCursor;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class BatchEvidenceReadService {
    private final BatchAssistMapper mapper;
    private final AssistMapper scopes;
    private final EvidenceSnapshotStore store;
    private final ObjectMapper json;
    public BatchEvidenceReadService(BatchAssistMapper mapper,AssistMapper scopes,EvidenceSnapshotStore store,ObjectMapper json) {
        this.mapper=mapper;this.scopes=scopes;this.store=store;this.json=json;
    }
    public String before(AssistScopeService.Scope scope) {
        if(scope.profile().contains("JUDGE")||scope.profile().contains("REVIEWER")) {
            var session=scopes.session(scope.externalSessionId());
            if(session==null) throw new AssistFailure("ASSIST_SCOPE_DENIED","会话不可用");
            return session.createdAt();
        }
        return "9999";
    }
    public Map<String,Object> directory(String owner,String before,String attempt,String cursor) {
        var page=PageCursor.decode(cursor);var rows=mapper.page(owner,blank(attempt),before,page==null?"":page.value(),page==null?"":page.id(),51);
        var visible=rows.stream().limit(50).toList();String next="";
        if(rows.size()>50) {var last=visible.getLast();next=new PageCursor(last.createdAt(),last.id()).encode();}
        return Map.of("items",visible.stream().map(store::metadata).toList(),"nextCursor",next,"historical",true);
    }
    public Map<String,Object> read(String owner,String before,String reference,int offset) {
        if(reference==null || !reference.startsWith("snapshot:")) throw new AssistFailure("EVIDENCE_REFERENCE_INVALID","请使用 snapshot: 引用");
        var row=mapper.evidence(owner,reference.substring(9),before);String content=store.read(row);
        if(offset<0||offset>content.length()) throw new AssistFailure("EVIDENCE_CURSOR_INVALID","请使用返回的 nextOffset");
        int end=Math.min(content.length(),offset+12000);
        if(end<content.length()&&end>offset&&Character.isHighSurrogate(content.charAt(end-1)))end--;
        var result=new LinkedHashMap<>(store.metadata(row));result.put("content",content.substring(offset,end));
        result.put("nextOffset",end<content.length()?end:-1);result.put("historical",true);return result;
    }
    public Map<String,Object> failures(String owner,String before,String attempt,String cursor) {
        var rows=mapper.failures(owner,before,blank(attempt),blank(cursor));var visible=rows.stream().limit(50).toList();
        return Map.of("items",visible,"nextCursor",rows.size()>50?visible.getLast().id():"","acceptance",false,
                "reports",directory(owner,before,attempt,null),"detail","用例列表只包含已解析的报告；缺失、零用例或来源未确认不能推断测试通过");
    }
    public Map<String,Object> failure(String owner,String before,String id) {
        var row=mapper.failure(owner,before,id);
        if(row==null)throw new AssistFailure("EVIDENCE_SCOPE_DENIED","失败用例不存在或不属于当前授权范围");
        var source=mapper.evidence(owner,row.snapshotId(),before);
        return Map.of("failure",json.readTree(row.detailJson()),"source",store.metadata(source),"acceptance",false);
    }
    public Map<String,Object> search(String owner,String before,String attempt,String query,String cursor) {
        if(query==null||query.isBlank()||query.length()>256)throw new AssistFailure("EVIDENCE_QUERY_INVALID","请输入 1 至 256 字符的搜索词");
        var page=PageCursor.decode(cursor);String time=page==null?"":page.value(),id=page==null?"":page.id();
        var rows=mapper.page(owner,blank(attempt),before,time,id,65);
        List<Map<String,Object>> hits=new ArrayList<>();long bytes=0,deadline=System.nanoTime()+3_000_000_000L;int chars=0;
        String next="";boolean complete=true;
        for(var row:rows.stream().limit(64).toList()) {
            if(System.nanoTime()>deadline||bytes+row.byteSize()>32L*1024*1024||hits.size()>=50||chars>=11500) {complete=false;next=new PageCursor(time,id).encode();break;}
            try {
                String text=store.read(row);bytes+=row.byteSize();int offset=0;
                while((offset=text.indexOf(query,offset))>=0) {
                    int start=Math.max(0,offset-100),end=Math.min(text.length(),offset+query.length()+150);
                    String excerpt=text.substring(start,end);chars+=excerpt.length();
                    hits.add(Map.of("reference","snapshot:"+row.id(),"offset",offset,"excerpt",excerpt,"source",row.source(),"status",row.status()));offset+=query.length();
                    if(hits.size()>=50||chars>=11500) {complete=false;break;}
                }
            } catch(AssistFailure unavailable) {complete=false;}
            time=row.createdAt();id=row.id();
        }
        if(rows.size()>64 && next.isEmpty()) {complete=false;next=new PageCursor(time,id).encode();}
        return Map.of("items",hits,"complete",complete,"nextCursor",next,"scannedBytes",bytes,"historical",true);
    }
    public Map<String,Object> tool(AssistScopeService.Scope scope,String name,Map<String,Object> args) {
        String owner=scope.ownerKey(),before=before(scope),attempt=value(args,"attemptId"),cursor=value(args,"cursor");
        return switch(name) {
            case "list_test_failures" -> failures(owner,before,attempt,cursor);
            case "read_test_failure" -> failure(owner,before,value(args,"failureId"));
            case "search_evidence" -> search(owner,before,attempt,value(args,"query"),cursor);
            default -> throw new AssistFailure("ASSIST_TOOL_UNKNOWN","工具不存在");
        };
    }
    private static String value(Map<String,Object> args,String key) {return args.get(key) instanceof String value?value:null;}
    private static String blank(String text) {return text==null?"":text;}
}
