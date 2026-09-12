package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.TemplateReportBundleMapper;
import io.opencode.loopper.persistence.TemplateReportBundleRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TemplateReportDownloadServiceTest {
    @Test void rejectsOversizedBundlesBeforeReadingBodiesAndRequiresAnOwnedReport() {
        var mapper = mock(TemplateReportBundleMapper.class);
        var service = new TemplateReportDownloadService(mapper);
        when(mapper.reportAttempt("t", "report")).thenReturn(Optional.of("a"));
        when(mapper.find("t", "a")).thenReturn(Optional.of(new TemplateReportBundleRow("a", "t", "ns", 1, "项目", "目录", "报告.md")));
        when(mapper.reportBytes("t", "a")).thenReturn(64L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> service.download("t", "report")).isInstanceOf(ConflictException.class).hasMessageContaining("上限");
        verify(mapper, never()).reports(any(), any());
        assertThatThrownBy(() -> service.download("other", "report")).isInstanceOf(NotFoundException.class);
        when(mapper.reportBytes("t", "a")).thenReturn(0L);
        when(mapper.reportCount("t", "a")).thenReturn(10_001);
        assertThatThrownBy(() -> service.download("t", "report")).isInstanceOf(ConflictException.class).hasMessageContaining("上限");
        verify(mapper, never()).reports(any(), any());
    }
}
