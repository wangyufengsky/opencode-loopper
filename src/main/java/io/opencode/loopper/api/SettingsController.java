package io.opencode.loopper.api;

import io.opencode.loopper.service.OpenCodeModelCatalogService;
import io.opencode.loopper.service.SettingsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsService.AppSettings get() {
        return settings.get();
    }

    @PutMapping
    public SettingsService.AppSettings save(@RequestBody SettingsService.AppSettings request) {
        return settings.save(request);
    }

    @GetMapping("/models")
    public List<OpenCodeModelCatalogService.AvailableModel> models(@RequestParam(required = false) String cliPath) {
        return settings.models(cliPath);
    }
}
