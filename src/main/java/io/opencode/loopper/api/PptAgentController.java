package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ppt.agent.PptAgentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ppt/documents/{id}")
public class PptAgentController {
    private final PptAgentService service;
    private final io.opencode.loopper.service.ppt.generation.PptGenerationService generation;
    public PptAgentController(PptAgentService service,io.opencode.loopper.service.ppt.generation.PptGenerationService generation) { this.service = service;this.generation=generation; }
    @GetMapping("/messages") public CursorPage<PptAgentService.Message> messages(@PathVariable String id,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) { return service.messages(id, cursor, limit); }
    @GetMapping("/agent") public PptAgentService.AgentStatus status(@PathVariable String id) { return service.status(id); }
    @PostMapping("/messages") public PptAgentService.Message send(@PathVariable String id, @RequestBody PptAgentService.Send input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String ui) { requireUi(ui); return generation.send(id, input); }
    @PostMapping("/questions/{questionId}/reply") public PptAgentService.Message reply(@PathVariable String id, @PathVariable String questionId,
            @RequestBody PptAgentService.Reply input, @RequestHeader(value="X-Loopper-Local-UI",required=false) String ui) {
        requireUi(ui); return service.reply(id, questionId, input);
    }
    @PostMapping("/stop") public PptAgentService.AgentStatus stop(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String ui) { requireUi(ui); return service.stop(id); }
    private static void requireUi(String value) {
        if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "此操作仅供本地 Loopper 界面使用");
    }
}
