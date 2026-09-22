package io.opencode.loopper.service.ppt;

import io.opencode.loopper.persistence.PptMapper;
import io.opencode.loopper.persistence.PptRows.Resource;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import io.opencode.loopper.service.assist.AssistFailure;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PptResources {
    private final PptMapper mapper;
    private final PptDocuments documents;
    private final PptStorage storage;
    private final AssistDocumentParser parser;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PptEvents events;
    public PptResources(PptMapper mapper,PptDocuments documents,PptStorage storage,AssistDocumentParser parser,
            ObjectMapper json,TransactionTemplate tx,PptEvents events) {
        this.mapper=mapper;this.documents=documents;this.storage=storage;this.parser=parser;this.json=json;this.tx=tx;this.events=events;
    }
    public record View(String id,String kind,String name,String mediaType,long bytes,String sha256,String state,
            String detail,String createdAt,int sections,List<String> limitations,String url) { }
    public Map<String,List<View>> list(String id) {
        documents.require(id);var rows=mapper.resources(id);
        return Map.of("sources",rows.stream().filter(r->"DOCUMENT".equals(r.kind())).map(this::view).toList(),
                "assets",rows.stream().filter(r->"IMAGE".equals(r.kind())).map(this::view).toList());
    }
    public View upload(String document,String name,byte[] bytes,String key,boolean image) {
        PptSupport.key(key);validateName(name);documents.require(document);
        if(bytes==null||bytes.length==0||bytes.length>20*1024*1024)throw PptSupport.bad("PPT_UPLOAD_SIZE","单文件必须非空且不超过 20 MiB");
        String media=image?imageType(bytes):"application/octet-stream",kind=image?"IMAGE":"DOCUMENT";
        String hash=PptSupport.hash(bytes),digest=PptSupport.hash(kind+":"+name+":"+hash);
        Resource row=tx.execute(status->{
            if(documents.require(document).archived())throw PptSupport.bad("PPT_ARCHIVED","请先恢复归档作品");
            var old=mapper.resourceReceipt(document,key);
            if(old.isPresent()) {if(!old.get().digest().equals(digest))throw PptSupport.conflict("同一上传标识已用于不同文件");return old.get();}
            var previous=mapper.resources(document).stream().filter(r->r.kind().equals(kind)&&!r.state().equals("FAILED")).toList();
            if(previous.size()>=(image?100:10)||previous.stream().mapToLong(Resource::bytes).sum()+bytes.length>(image?200L:50L)*1024*1024)
                throw PptSupport.bad("PPT_UPLOAD_LIMIT",image?"每份作品最多 100 张图片、总计 200 MiB":"每份作品最多 10 份资料、总计 50 MiB");
            String id=UUID.randomUUID().toString();
            Resource resource=new Resource(id,document,kind,name,media,"resources/"+id,hash,bytes.length,"PREPARING","正在保存与解析",null,key,digest,Instant.now().toString());
            if(mapper.insertResource(resource)!=1)throw PptSupport.conflict("上传保存失败");return resource;
        });
        if("READY".equals(row.state())) {storage.verified(document,row.storageKey(),row.bytes(),row.sha256());return view(row);}
        try {
            storage.write(document,row.storageKey(),bytes);
            String body=image?null:json.writeValueAsString(parser.parse(name,bytes));
            tx.executeWithoutResult(status->{if(mapper.resourceState(row.id(),"READY","",body)!=1&&!"READY".equals(require(document,row.id()).state()))throw PptSupport.conflict("资料保存状态已变化");});
        }catch(RuntimeException failure) {
            mapper.resourceState(row.id(),"FAILED",failure instanceof AssistFailure?failure.getMessage():"保存或解析失败，请使用原请求重试上传",null);
        }
        events.publish(document,"sources");return view(require(document,row.id()));
    }
    public JsonNode source(String document,String id) {
        var row=require(document,id);if(!"DOCUMENT".equals(row.kind())||!"READY".equals(row.state()))throw PptSupport.bad("PPT_SOURCE_UNAVAILABLE","资料尚不可读，请重试上传");
        storage.verified(document,row.storageKey(),row.bytes(),row.sha256());
        var result=(tools.jackson.databind.node.ObjectNode)json.readTree(row.bodyJson());result.put("id",row.id());result.put("name",row.name());return result;
    }
    public JsonNode read(String document,String id,String section,int offset,int limit) {
        if(offset<0||limit<1||limit>12000)throw PptSupport.bad("PPT_SOURCE_RANGE","资料每次读取 1–12000 个字符");
        var source=source(document,id);
        for(var item:source.path("sections"))if(item.path("id").asText().equals(section)) {
            String text=item.path("markdown").asText();if(offset>text.length())throw PptSupport.bad("PPT_SOURCE_RANGE","读取位置超过资料范围");
            return json.valueToTree(Map.of("sourceId",id,"sectionId",section,"name",source.path("name").asText(),"title",item.path("title").asText(),
                    "offset",offset,"text",text.substring(offset,Math.min(text.length(),offset+limit)),"total",text.length()));
        }
        throw new NotFoundException("资料片段不存在");
    }
    public byte[] asset(String document,String id) {
        var row=require(document,id);if(!"IMAGE".equals(row.kind())||!"READY".equals(row.state()))throw new NotFoundException("图片素材不存在");
        return storage.verified(document,row.storageKey(),row.bytes(),row.sha256());
    }
    public Resource require(String document,String id) {documents.require(document);return mapper.resource(document,id).orElseThrow(()->new NotFoundException("资料不属于当前作品"));}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${loopper.ppt-upload-recovery-delay:30000}")
    public void recoverUploads() {
        for(var row:mapper.preparingResources(Instant.now().minusSeconds(60).toString())) {
            try {
                var bytes=storage.verified(row.documentId(),row.storageKey(),row.bytes(),row.sha256());
                String body="DOCUMENT".equals(row.kind())?json.writeValueAsString(parser.parse(row.name(),bytes)):null;
                if("IMAGE".equals(row.kind()))imageType(bytes);
                mapper.resourceState(row.id(),"READY","",body);
            }catch(RuntimeException failure){mapper.resourceState(row.id(),"FAILED","上传中断，已保存作品不受影响；请重新选择这份文件上传",null);}
            events.publish(row.documentId(),"sources");
        }
    }
    private View view(Resource row) {
        JsonNode body=row.bodyJson()==null?json.createObjectNode():json.readTree(row.bodyJson());var limitations=new ArrayList<String>();body.path("limitations").forEach(n->limitations.add(n.asText()));
        return new View(row.id(),row.kind(),row.name(),row.mediaType(),row.bytes(),row.sha256(),row.state(),row.detail(),row.createdAt(),
                body.has("sectionCount")?body.path("sectionCount").asInt():body.path("sections").size(),limitations,
                "IMAGE".equals(row.kind())?"/api/ppt/documents/"+row.documentId()+"/assets/"+row.id():null);
    }
    private void validateName(String name) {
        if(name==null||name.isBlank()||name.length()>240||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl))throw PptSupport.bad("PPT_FILENAME_INVALID","文件名无效，请重命名后上传");
    }
    private String imageType(byte[] bytes) {
        try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IllegalArgumentException();var reader=readers.next();
            try {reader.setInput(in);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>40_000_000)throw new IllegalArgumentException();
                return "png".equals(format)?"image/png":"image/jpeg";
            }finally {reader.dispose();}
        }catch(Exception failure){throw PptSupport.bad("PPT_IMAGE_INVALID","仅支持不超过 4000 万像素的 PNG 或 JPEG 图片");}
    }
}
