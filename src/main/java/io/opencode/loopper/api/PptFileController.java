package io.opencode.loopper.api;

import io.opencode.loopper.service.ppt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ppt/documents/{id}")
public class PptFileController {
    private final PptResources resources;
    private final PptJobs jobs;
    public PptFileController(PptResources resources,PptJobs jobs){this.resources=resources;this.jobs=jobs;}
    @GetMapping("/sources") public Object sources(@PathVariable String id){return resources.list(id);}
    @PostMapping(value="/sources",consumes="multipart/form-data") public Object source(@PathVariable String id,@RequestPart MultipartFile file,@RequestParam String idempotencyKey,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui)throws IOException{KnowledgeController.requireUi(ui);return resources.upload(id,file.getOriginalFilename(),file.getBytes(),idempotencyKey,false);}
    @PostMapping(value="/assets",consumes="multipart/form-data") public Object asset(@PathVariable String id,@RequestPart MultipartFile file,@RequestParam String idempotencyKey,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui)throws IOException{KnowledgeController.requireUi(ui);return resources.upload(id,file.getOriginalFilename(),file.getBytes(),idempotencyKey,true);}
    @GetMapping("/sources/{source}") public Object source(@PathVariable String id,@PathVariable String source){return resources.source(id,source);}
    @GetMapping("/assets/{asset}") public ResponseEntity<byte[]> asset(@PathVariable String id,@PathVariable String asset){var row=resources.require(id,asset);return binary(resources.asset(id,asset),row.mediaType(),row.name(),false);}
    @PostMapping("/jobs") public Object create(@PathVariable String id,@RequestBody PptJobs.Create input,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return jobs.create(id,input,()->{});}
    @GetMapping("/jobs") public Object jobs(@PathVariable String id){return jobs.list(id);}
    @GetMapping("/jobs/{job}") public Object job(@PathVariable String id,@PathVariable String job){return jobs.get(id,job);}
    @PostMapping("/jobs/{job}/cancel") public Object cancel(@PathVariable String id,@PathVariable String job,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return jobs.cancel(id,job);}
    @PostMapping("/jobs/{job}/retry") public Object retry(@PathVariable String id,@PathVariable String job,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return jobs.retry(id,job);}
    @GetMapping("/artifacts/{artifact}") public ResponseEntity<byte[]> artifact(@PathVariable String id,@PathVariable String artifact){var row=jobs.artifact(id,artifact);return binary(jobs.download(id,artifact),row.mediaType(),row.name(),!"image/png".equals(row.mediaType()));}
    private ResponseEntity<byte[]> binary(byte[] data,String media,String name,boolean attachment){return ResponseEntity.ok().contentType(MediaType.parseMediaType(media))
            .header(HttpHeaders.CONTENT_DISPOSITION,(attachment?ContentDisposition.attachment():ContentDisposition.inline()).filename(name,StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(data);}
}
