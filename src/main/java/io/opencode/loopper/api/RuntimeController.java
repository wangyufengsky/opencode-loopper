package io.opencode.loopper.api;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.runtime.OpenCodeRuntimeManager;
import io.opencode.loopper.runtime.OpenCodeCapabilityService;
import io.opencode.loopper.service.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/runtime/opencode")
public class RuntimeController {
    private final OpenCodeRuntimeManager runtimeManager;
    private final OpenCodeCapabilityService capabilityService;
    private final String loopperVersion;
    public RuntimeController(OpenCodeRuntimeManager runtimeManager, OpenCodeCapabilityService capabilityService,
                             @Value("${spring.ai.mcp.server.version:unknown}") String loopperVersion) {
        this.runtimeManager = runtimeManager;
        this.capabilityService = capabilityService;
        String packagedVersion = LoopperApplication.class.getPackage().getImplementationVersion();
        this.loopperVersion = packagedVersion == null || packagedVersion.isBlank()
                ? loopperVersion : packagedVersion;
    }
    @GetMapping public RuntimeDto runtime() { return dto(runtimeManager.status()); }
    @PostMapping("/start") public RuntimeDto start(
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        if (!runtimeManager.manuallyStartable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only managed or auto mode can start a Loopper-owned OpenCode runtime");
        }
        return dto(runtimeManager.startAndCheck());
    }
    @PostMapping("/restart") public RuntimeDto restart(
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        if (!runtimeManager.restartable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an OpenCode process managed by this Loopper instance can be restarted");
        }
        return dto(runtimeManager.restartOwned());
    }
    private RuntimeDto dto(OpenCodeRuntimeManager.RuntimeSnapshot snapshot) {
        var readiness = snapshot.internalMcp();
        InternalMcpDto internalMcp = readiness == null ? null : new InternalMcpDto(
                readiness.status(), snapshot.internalMcpServer() != null && !snapshot.internalMcpServer().isBlank(),
                readiness.detail());
        return new RuntimeDto(loopperVersion, snapshot.status(), snapshot.version(), snapshot.managed(), snapshot.pid(),
                snapshot.endpoint(), snapshot.model(), snapshot.checkedAt().toString(), snapshot.startupFailure(),
                shortGeneration(snapshot.generation()), internalMcp,
                capabilityService.capabilities(snapshot));
    }
    public record RuntimeDto(String loopperVersion, String status, String version, boolean managed, Long pid, String endpoint,
                             String model, String checkedAt, String startupFailure, String generation,
                             InternalMcpDto internalMcp,
                             OpenCodeCapabilityService.RuntimeCapabilities capabilities) { }
    public record InternalMcpDto(String status, boolean configured, String detail) { }

    private static String shortGeneration(String generation) {
        if (generation == null || generation.isBlank()) return null;
        return generation.substring(0, Math.min(8, generation.length()));
    }

    private static void requireLocalUi(String localUi) {
        if (!"1".equals(localUi)) {
            throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED",
                    "This operation is available only to the local Loopper UI");
        }
    }
}
