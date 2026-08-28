package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.List;

/** Deterministically converts a frozen requirement snapshot into stable numbered segments. */
final class DesignerRequirementSegmenter {
    private DesignerRequirementSegmenter() { }

    static List<DesignerSessionService.RequirementSegment> segment(String requirement) {
        String normalized = requirement.replace("\r\n", "\n");
        List<String> sections = markdownSections(normalized);
        if (!sections.isEmpty()) {
            List<DesignerSessionService.RequirementSegment> grouped = new ArrayList<>();
            for (String section : sections) grouped.add(new DesignerSessionService.RequirementSegment(
                    "RQ-" + (grouped.size() + 1), section));
            return List.copyOf(grouped);
        }
        List<DesignerSessionService.RequirementSegment> result = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;
            List<String> items = new ArrayList<>();
            StringBuilder prose = new StringBuilder();
            for (String rawLine : trimmed.split("\\n")) {
                String line = rawLine.trim();
                if (line.matches("^(?:[-*+]\\s+|\\d+[.)、]\\s*).+")) {
                    if (!prose.isEmpty()) { items.add(prose.toString().trim()); prose.setLength(0); }
                    items.add(line.replaceFirst("^(?:[-*+]\\s+|\\d+[.)、]\\s*)", "").trim());
                } else {
                    if (!prose.isEmpty()) prose.append(' ');
                    prose.append(line);
                }
            }
            if (!prose.isEmpty()) items.add(prose.toString().trim());
            for (String item : items) if (!item.isBlank()) result.add(
                    new DesignerSessionService.RequirementSegment("RQ-" + (result.size() + 1), item));
        }
        if (result.isEmpty()) throw new BadRequestException(
                "DESIGNER_MESSAGE_REQUIRED", "Requirement text is empty");
        return List.copyOf(result);
    }

    private static List<String> markdownSections(String requirement) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = null;
        for (String rawLine : requirement.split("\\n", -1)) {
            String line = rawLine.trim();
            if (line.matches("^##\\s+.+")) {
                if (current != null && !current.toString().isBlank()) sections.add(current.toString().trim());
                current = new StringBuilder(line.replaceFirst("^##\\s+", ""));
            } else if (current != null && !line.matches("^-{3,}$")) current.append('\n').append(rawLine);
        }
        if (current != null && !current.toString().isBlank()) sections.add(current.toString().trim());
        return sections;
    }
}
