package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ppt.generation.PptGenerationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ppt/documents/{id}")
public class PptGenerationController {
    private final PptGenerationService service;
    public PptGenerationController(PptGenerationService service) { this.service=service; }
    @PostMapping("/generate") public PptGenerationService.View generate(@PathVariable String id,@RequestBody PptGenerationService.Generate input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false)String ui) { requireUi(ui);return service.generate(id,input); }
    @PostMapping("/generate/confirm") public PptGenerationService.View confirm(@PathVariable String id,@RequestBody PptGenerationService.Confirm input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false)String ui) { requireUi(ui);return service.confirmRequirements(id,input); }
    @GetMapping("/generation") public PptGenerationService.View status(@PathVariable String id) { return service.status(id); }
    @PostMapping("/generate/resume") public PptGenerationService.View resume(@PathVariable String id,@RequestBody PptGenerationService.Resume input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false)String ui) { requireUi(ui);return service.resume(id,input); }
    private static void requireUi(String ui) { if(!"1".equals(ui))throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED","此操作仅供本地 Loopper 界面使用"); }
}
