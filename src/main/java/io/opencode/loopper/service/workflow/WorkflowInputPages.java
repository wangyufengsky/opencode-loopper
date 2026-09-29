package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;

/** Shared HTTP/MCP pagination: UTF-16 offsets, code-point limits, no split surrogate pairs. */
public final class WorkflowInputPages {
    private WorkflowInputPages() { }
    public record Page(String name, WorkflowGraph.DataKind kind, String sha256, String text,
                       int offset, Integer nextOffset, int totalLength) { }
    static String text(WorkflowDelivery.Input input, WorkflowEncoding encoding) {
        return input.kind() == WorkflowGraph.DataKind.TEXT ? input.content().asString() : encoding.encode(input.content());
    }
    static Page page(WorkflowDelivery.Input input, String content, int offset, int limit) {
        if (offset < 0 || offset > 2_097_152 || limit < 1 || limit > 12_000 || offset > content.length()
                || offset > 0 && offset < content.length() && Character.isLowSurrogate(content.charAt(offset))
                && Character.isHighSurrogate(content.charAt(offset - 1)))
            throw new BadRequestException("WORKFLOW_INPUT_PAGE_INVALID", "读取位置或长度无效，请从正文起始位置重新读取。");
        int end = offset;
        for (int count = 0; count < limit && end < content.length(); count++) end += Character.charCount(content.codePointAt(end));
        return new Page(input.name(), input.kind(), input.sha256(), content.substring(offset, end), offset,
                end < content.length() ? end : null, content.length());
    }
}
