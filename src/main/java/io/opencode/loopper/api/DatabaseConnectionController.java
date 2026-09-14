package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.assist.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/database-connections")
public class DatabaseConnectionController {
    private final DatabaseConnectionService connections;private final DatabaseQueryService queries;private final DatabaseDriverRegistry drivers;
    public DatabaseConnectionController(DatabaseConnectionService connections,DatabaseQueryService queries,DatabaseDriverRegistry drivers){this.connections=connections;this.queries=queries;this.drivers=drivers;}
    @GetMapping public CursorPage<DatabaseConnectionService.View> list(@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,@RequestParam(required=false)String query,@RequestParam(required=false)String type,@RequestParam(required=false)String state){return connections.list(cursor,limit,query,type,state);}
    @GetMapping("/types") public List<BundledDatabaseDrivers.Profile> types(){return BundledDatabaseDrivers.defaults();}
    @PostMapping("/test") public Map<String,Object> draftTest(@RequestHeader("X-Loopper-Local-UI")String ui,@RequestParam(required=false)String id,@RequestBody DatabaseConnectionService.Request body){
        local(ui);var bound=connections.draft(id,body);return queries.test(bound,body.password());
    }
    @GetMapping("/drivers") public List<DatabaseDriverRegistry.DriverInfo> drivers(){return drivers.inventory();}
    @GetMapping("/{id}") public DatabaseConnectionService.View get(@PathVariable String id){return connections.get(id);}
    @PostMapping public DatabaseConnectionService.View create(@RequestHeader("X-Loopper-Local-UI")String ui,@RequestBody DatabaseConnectionService.Request body){local(ui);return connections.save(null,body);}
    @PutMapping("/{id}") public DatabaseConnectionService.View update(@RequestHeader("X-Loopper-Local-UI")String ui,@PathVariable String id,@RequestBody DatabaseConnectionService.Request body){local(ui);return connections.save(id,body);}
    @PostMapping("/{id}/test") public Map<String,Object> test(@RequestHeader("X-Loopper-Local-UI")String ui,@PathVariable String id){local(ui);return queries.test(connections.forTest(id));}
    static void local(String ui){if(!"1".equals(ui))throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED","请从 Loopper 本地管理页面执行此操作");}
}
