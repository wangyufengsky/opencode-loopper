package io.opencode.loopper.api;

import io.opencode.loopper.runtime.OpenCodeSkillInventory;
import io.opencode.loopper.runtime.OpenCodeToolInventory;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ProjectService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenCodeToolsControllerTest {
    @Test void unknownProjectCannotFallBackToGlobalSkills() {
        var projects = mock(ProjectService.class);
        var skills = mock(OpenCodeSkillInventory.class);
        var controller = new OpenCodeToolsController(mock(OpenCodeToolInventory.class), projects, skills);
        when(projects.get("unknown")).thenThrow(new NotFoundException("项目不存在"));
        assertThatThrownBy(() -> controller.skills("unknown")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.skillDocument("unknown", "review")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(skills);
        controller.skills("");
        verify(skills).inventory(java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath());
    }
}
