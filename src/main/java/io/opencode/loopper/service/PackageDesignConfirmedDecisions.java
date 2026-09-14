package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit source-addressed user feedback; candidate prose and AI messages can never resolve a business decision. */
final class PackageDesignConfirmedDecisions {
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\s*(REQ-L[0-9]{3}|RQ-[1-9][0-9]*)\\s*[=:：]\\s*([^\\r\\n]{1,1000})\\s*$");
    private PackageDesignConfirmedDecisions() { }
    static Map<String, String> load(LoopperDesignerMapper mapper, DesignWorkPackageRow owner, String original, int requirementRevision) {
        var known = PackageRequirementSources.index(original).keySet();
        Map<String, String> decisions = new LinkedHashMap<>();
        for (var discussion : mapper.listDesignDiscussionRevisions(owner.designerSessionId())) {
            if (!owner.packageId().equals(discussion.scopeKey()) || discussion.sourceMessageId() == null
                    || discussion.requirementRevision() == null || discussion.requirementRevision() != requirementRevision) continue;
            mapper.findDesignerMessage(discussion.sourceMessageId())
                    .filter(message -> "USER".equals(message.actor()) && owner.designerSessionId().equals(message.designerSessionId())
                            && owner.packageId().equals(message.workPackageId()))
                    .ifPresent(message -> decisions.putAll(parse(message.content(), known)));
        }
        return Map.copyOf(decisions);
    }
    static Map<String, String> parse(String text, Set<String> sources) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) return result;
        var matcher = ENTRY.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2).strip();
            if (sources.contains(matcher.group(1)) && !value.matches("(?s).*(?:尚未|待确认|待决定|未定|还是|或者|不确定).*"))
                result.put(matcher.group(1), value);
        }
        return Map.copyOf(result);
    }
}
