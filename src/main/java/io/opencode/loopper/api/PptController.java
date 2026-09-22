package io.opencode.loopper.api;

import io.opencode.loopper.service.ppt.*;
import io.opencode.loopper.ppt.PptEngine;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ppt")
public class PptController {
    private final PptDocuments documents;
    private final PptEngine engine;
    private final PptCommands commands;
    private final PptEvents events;
    private final PptChecks checks;
    public PptController(PptDocuments documents,PptEngine engine,PptCommands commands,PptEvents events,PptChecks checks){this.documents=documents;this.engine=engine;this.commands=commands;this.events=events;this.checks=checks;}
    @GetMapping("/capabilities") public Object capabilities(){return engine.capabilities();}
    @GetMapping("/documents") public Object list(@RequestParam(defaultValue="active")String archive,@RequestParam(defaultValue="")String query,
            @RequestParam(defaultValue="")String phase,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return documents.list(archive,query,phase,cursor,limit);}
    @PostMapping("/documents") public Object create(@RequestBody PptDocuments.Create input,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return documents.create(input);}
    @GetMapping("/documents/{id}") public Object get(@PathVariable String id){return documents.get(id);}
    @GetMapping("/documents/{id}/deck") public Object deck(@PathVariable String id,@RequestParam(required=false)Long revision){return documents.deck(id,revision);}
    @GetMapping("/documents/{id}/plan") public Object plan(@PathVariable String id){return documents.plan(id);}
    @PostMapping("/documents/{id}/plan") public Object plan(@PathVariable String id,@RequestBody PptDocuments.PlanEdit input,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return documents.savePlan(id,input,false,()->{});}
    @PostMapping("/documents/{id}/operations") public Object operations(@PathVariable String id,@RequestBody PptDocuments.Edit input,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return documents.edit(id,input,false,()->{});}
    @PostMapping("/documents/{id}/actions/{action}") public Object action(@PathVariable String id,@PathVariable String action,@RequestBody PptDocuments.Action input,@RequestHeader(value="X-Loopper-Local-UI",required=false)String ui){KnowledgeController.requireUi(ui);return commands.action(id,action,input);}
    @GetMapping("/documents/{id}/revisions") public Object revisions(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit){return documents.revisions(id,cursor,limit);}
    @GetMapping("/documents/{id}/checks") public Object checks(@PathVariable String id,@RequestParam(required=false)Long revision){return checks.check(id,revision);}
    @GetMapping(value="/documents/{id}/events",produces="text/event-stream") public SseEmitter stream(@PathVariable String id){
        documents.get(id);var emitter=new SseEmitter(0L);var lifecycle=new SseEmitterLifecycle();
        emitter.onCompletion(lifecycle::close);emitter.onTimeout(lifecycle::close);emitter.onError(failure->lifecycle.close());
        lifecycle.attach(events.subscribe(id,event->lifecycle.send(()->emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(event)))));
        lifecycle.send(()->emitter.send(SseEmitter.event().data(Map.of("type","connected","documentId",id))));return emitter;
    }
}
