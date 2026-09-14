package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/template-tasks/document-runs/{id}/supplements")
public final class DocumentSupplementController {
    private final DocumentSupplementService supplements;
    private final DocumentTemplateReadService reads;
    private final DocumentTemplateCoordinator coordinator;
    public DocumentSupplementController(DocumentSupplementService supplements,DocumentTemplateReadService reads,DocumentTemplateCoordinator coordinator) {
        this.supplements=supplements;this.reads=reads;this.coordinator=coordinator;
    }
    @GetMapping public DocumentSupplementService.Options options(@PathVariable String id) { return supplements.options(id); }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentTemplateReadService.Overview upload(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi,
            @RequestPart("metadata") DocumentSupplementService.Request request,@RequestPart("files") List<MultipartFile> files) throws IOException {
        if(!"1".equals(localUi)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED","请从本地页面补充需求文档");
        if(files.isEmpty() || files.size()>10) throw new BadRequestException("DOCUMENT_TEMPLATE_FILES_REQUIRED","请选择 1–10 份补充文档");
        long total=0;
        for(var file:files) {
            if(file.getSize()==0 || file.getSize()>DocumentTemplateStorage.MAX_FILE_BYTES)
                throw new BadRequestException("DOCUMENT_TEMPLATE_FILE_SIZE","单文件不能为空或超过 20 MiB");
            total+=file.getSize();
        }
        if(total>DocumentTemplateStorage.MAX_BATCH_BYTES) throw new BadRequestException("DOCUMENT_TEMPLATE_BATCH_SIZE","补充文档总大小超过 50 MiB");
        var incoming=new ArrayList<DocumentTemplateStorage.Incoming>();
        for(var file:files) incoming.add(new DocumentTemplateStorage.Incoming(file.getOriginalFilename(),file.getBytes()));
        supplements.upload(id,request,incoming); coordinator.dispatch(id); return reads.overview(id);
    }
}
