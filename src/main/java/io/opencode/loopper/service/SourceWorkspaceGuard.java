package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Shared admission check for capturing current source without an overlapping admitted writer. */
@Component
public final class SourceWorkspaceGuard {
    private final LoopperMapper domain;
    public SourceWorkspaceGuard(LoopperMapper domain){this.domain=domain;}
    public void requireNoWriter(Path root) {
        for(var lease:domain.blockingWorkspaceLeases()) {
            Path leased=Path.of(lease.canonicalRoot()).toAbsolutePath().normalize();
            if(root.startsWith(leased)||leased.startsWith(root))
                throw new ConflictException("SOURCE_WORKSPACE_BUSY","项目存在活动写入任务或停止待确认，请处理后重新检查");
        }
    }
}
