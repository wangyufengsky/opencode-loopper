package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.LoopSpec;
import tools.jackson.databind.ObjectMapper;

/** Generates deterministic compatibility responses for the fake OpenCode adapter. */
final class FakeOpenCodeResponseFactory {
    String designerMarkdown(String output) {
        if (output == null) return null;
        return output.replaceAll("(?is)<!--\\s*LOOPSPEC_JSON_START\\s*-->.*?<!--\\s*LOOPSPEC_JSON_END\\s*-->", "").trim();
    }
    String compatibilityCompilation(String output) {
        if (output == null) return null;
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                "(?is)<!--\\s*LOOPSPEC_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_JSON_END\\s*-->").matcher(output);
        if (!marker.find()) return null;
        String payload = marker.group(1);
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            String specPayload = payload.substring(start, end + 1);
            LoopSpec spec = mapper.readValue(specPayload, LoopSpec.class);
            tools.jackson.databind.node.ObjectNode specNode = (tools.jackson.databind.node.ObjectNode) mapper.readTree(specPayload);
            String excerpt = designerMarkdown(output);
            if (excerpt == null || excerpt.isBlank()) excerpt = "设计稿";
            java.util.List<java.util.Map<String, Object>> sources = new java.util.ArrayList<>();
            tools.jackson.databind.node.ArrayNode stageNodes = (tools.jackson.databind.node.ArrayNode) specNode.get("stages");
            for (int stageIndex = 0; stageIndex < stageNodes.size(); stageIndex++) {
                tools.jackson.databind.node.ObjectNode stage = (tools.jackson.databind.node.ObjectNode) stageNodes.get(stageIndex);
                stage.put("workPackageId", "WP-1");
                tools.jackson.databind.JsonNode criteria = stage.get("acceptanceCriteria");
                if (criteria != null && criteria.isArray()) for (tools.jackson.databind.JsonNode value : criteria) {
                    tools.jackson.databind.node.ObjectNode criterion = (tools.jackson.databind.node.ObjectNode) value;
                    String original = criterion.path("id").asText();
                    String mapped = original.startsWith("WP-1-") ? original : "WP-1-" + original;
                    criterion.put("id", mapped);
                    sources.add(java.util.Map.of("stageIndex", stageIndex, "criterionId", mapped, "excerpt", excerpt));
                }
                tools.jackson.databind.JsonNode verifiers = stage.get("verifiers");
                if (verifiers != null && verifiers.isArray()) for (tools.jackson.databind.JsonNode value : verifiers) {
                    tools.jackson.databind.node.ObjectNode verifier = (tools.jackson.databind.node.ObjectNode) value;
                    tools.jackson.databind.JsonNode ids = verifier.get("criterionIds");
                    if (ids != null && ids.isArray()) {
                        tools.jackson.databind.node.ArrayNode mapped = mapper.createArrayNode();
                        for (tools.jackson.databind.JsonNode id : ids) {
                            String original = id.asText();
                            mapped.add(original.startsWith("WP-1-") ? original : "WP-1-" + original);
                        }
                        verifier.set("criterionIds", mapped);
                    }
                }
            }
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("status", "COMPILED");
            envelope.put("summary", "LoopSpec 已由测试用只读规范工程师生成。");
            envelope.put("stages", stageNodes);
            envelope.put("criterionSources", sources);
            envelope.put("handoffSummary", "WP-1 已完成，可执行聚合后的后续阶段。");
            envelope.put("designGaps", java.util.List.of());
            return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n```json\n"
                    + mapper.writeValueAsString(envelope)
                    + "\n```\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
        } catch (Exception invalid) {
            return null;
        }
    }
    String directDecomposition(String output) {
        String goal = "设计并交付当前需求";
        try {
            java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                    "(?is)<!--\\s*LOOPSPEC_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_JSON_END\\s*-->").matcher(output);
            if (marker.find()) {
                LoopSpec spec = new ObjectMapper().readValue(marker.group(1).replace("```json", "").replace("```", "").trim(), LoopSpec.class);
                goal = spec.goal();
            }
            java.util.Map<String, Object> workPackage = new java.util.LinkedHashMap<>();
            workPackage.put("id", "WP-1");
            workPackage.put("title", "完整需求交付");
            workPackage.put("objective", goal);
            workPackage.put("scopeIn", java.util.List.of("当前需求涉及的业务能力"));
            workPackage.put("scopeOut", java.util.List.of("独立项目根和独立发布边界"));
            workPackage.put("dependencies", java.util.List.of());
            workPackage.put("deliverables", java.util.List.of("可验证实现"));
            workPackage.put("acceptanceIntent", java.util.List.of("需求中的可观察结果通过确定性验证"));
            String markdown = designerMarkdown(output);
            int segmentCount = markdown == null ? 1 : Math.max(1, (int) java.util.Arrays.stream(
                            markdown.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n"))
                    .map(String::trim).filter(value -> !value.isBlank()).count());
            java.util.List<String> requirementRefs = java.util.stream.IntStream.rangeClosed(1, segmentCount)
                    .mapToObj(index -> "RQ-" + index).toList();
            workPackage.put("requirementRefs", requirementRefs);
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("status", "DIRECT_DESIGN");
            envelope.put("normalizedGoal", goal);
            envelope.put("globalConstraints", java.util.List.of());
            envelope.put("workPackages", java.util.List.of(workPackage));
            envelope.put("designGaps", java.util.List.of());
            envelope.put("reason", null);
            return "<!-- TASK_DECOMPOSITION_JSON_START -->\n" + new ObjectMapper().writeValueAsString(envelope)
                    + "\n<!-- TASK_DECOMPOSITION_JSON_END -->";
        } catch (Exception invalid) {
            return "<!-- TASK_DECOMPOSITION_JSON_START -->\n{}\n<!-- TASK_DECOMPOSITION_JSON_END -->";
        }
    }
    String decompositionPlanningOutput(String finalOutput) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            tools.jackson.databind.node.ObjectNode source = markedObject(finalOutput,
                    "TASK_DECOMPOSITION_JSON_START", "TASK_DECOMPOSITION_JSON_END", mapper);
            tools.jackson.databind.node.ObjectNode plan = mapper.createObjectNode();
            plan.set("status", source.path("status"));
            plan.set("normalizedGoal", source.path("normalizedGoal"));
            tools.jackson.databind.node.ArrayNode constraints = source.path("globalConstraints").isArray()
                    ? (tools.jackson.databind.node.ArrayNode) source.path("globalConstraints") : mapper.createArrayNode();
            tools.jackson.databind.node.ArrayNode packages = source.path("workPackages").isArray()
                    ? (tools.jackson.databind.node.ArrayNode) source.path("workPackages") : mapper.createArrayNode();
            plan.set("globalConstraints", constraints);
            plan.set("workPackages", packages);
            tools.jackson.databind.node.ArrayNode coverage = mapper.createArrayNode();
            for (int index = 0; index < constraints.size(); index++) {
                tools.jackson.databind.JsonNode constraint = constraints.get(index);
                for (tools.jackson.databind.JsonNode ref : constraint.path("requirementRefs")) {
                    tools.jackson.databind.node.ObjectNode mapping = coverage.addObject();
                    mapping.put("requirementRef", ref.asText());
                    mapping.put("targetType", "GLOBAL_CONSTRAINT");
                    mapping.put("targetId", "GC-" + (index + 1));
                    mapping.put("rationale", "测试规划将该需求段归入对应全局约束。");
                }
            }
            tools.jackson.databind.node.ArrayNode dependencies = mapper.createArrayNode();
            for (tools.jackson.databind.JsonNode workPackage : packages) {
                for (tools.jackson.databind.JsonNode ref : workPackage.path("requirementRefs")) {
                    tools.jackson.databind.node.ObjectNode mapping = coverage.addObject();
                    mapping.put("requirementRef", ref.asText());
                    mapping.put("targetType", "WORK_PACKAGE");
                    mapping.put("targetId", workPackage.path("id").asText());
                    mapping.put("rationale", "测试规划将该需求段归入当前纵向能力包。");
                }
                for (tools.jackson.databind.JsonNode dependency : workPackage.path("dependencies")) {
                    tools.jackson.databind.node.ObjectNode evidence = dependencies.addObject();
                    evidence.put("workPackageId", workPackage.path("id").asText());
                    evidence.put("dependsOn", dependency.asText());
                    evidence.put("rationale", "当前包使用前置包的已交付能力。");
                }
            }
            plan.set("coverageMappings", coverage);
            plan.set("dependencyEvidence", dependencies);
            plan.set("designGaps", source.path("designGaps").isArray()
                    ? source.path("designGaps") : mapper.createArrayNode());
            plan.set("reason", source.path("reason"));
            return "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->\n" + mapper.writeValueAsString(plan)
                    + "\n<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->";
        } catch (Exception invalid) {
            return "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->\n{}\n<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->";
        }
    }

    String packageCompilationPlanningOutput(String finalOutput) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            tools.jackson.databind.node.ObjectNode source = markedObject(finalOutput,
                    "LOOPSPEC_COMPILATION_JSON_START", "LOOPSPEC_COMPILATION_JSON_END", mapper);
            tools.jackson.databind.node.ObjectNode plan = mapper.createObjectNode();
            boolean v2 = source.path("stages").isArray()
                    && java.util.stream.StreamSupport.stream(source.path("stages").spliterator(), false)
                    .anyMatch(stage -> !stage.path("implementationKind").isMissingNode()
                            && !stage.path("implementationKind").isNull());
            plan.put("contractVersion", v2 ? 2 : 1);
            plan.set("status", source.path("status"));
            plan.set("summary", source.path("summary"));
            tools.jackson.databind.node.ArrayNode stagePlans = mapper.createArrayNode();
            tools.jackson.databind.node.ArrayNode evidenceMappings = mapper.createArrayNode();
            tools.jackson.databind.JsonNode stages = source.path("stages");
            for (int stageIndex = 0; stages.isArray() && stageIndex < stages.size(); stageIndex++) {
                tools.jackson.databind.JsonNode stage = stages.get(stageIndex);
                tools.jackson.databind.node.ObjectNode stagePlan = stagePlans.addObject();
                stagePlan.set("objective", stage.path("objective"));
                stagePlan.set("allowedPaths", stage.path("allowedPaths"));
                stagePlan.set("forbiddenPaths", stage.path("forbiddenPaths"));
                stagePlan.set("deliverables", stage.path("deliverables"));
                stagePlan.set("verifiers", stage.path("verifiers"));
                stagePlan.set("verificationRuntime", stage.path("verificationRuntime"));
                stagePlan.set("implementationKind", stage.path("implementationKind"));
                stagePlan.set("workPackageId", stage.path("workPackageId"));
                tools.jackson.databind.JsonNode criteria = stage.path("acceptanceCriteria");
                for (tools.jackson.databind.JsonNode criterion : criteria) {
                    String criterionId = criterion.path("id").asText();
                    tools.jackson.databind.node.ObjectNode mapping = evidenceMappings.addObject();
                    mapping.put("stageIndex", stageIndex);
                    mapping.set("criterionId", criterion.path("id"));
                    mapping.set("description", criterion.path("description"));
                    String excerpt = "设计稿";
                    for (tools.jackson.databind.JsonNode sourceEntry : source.path("criterionSources")) {
                        if (sourceEntry.path("stageIndex").asInt() == stageIndex
                                && criterionId.equals(sourceEntry.path("criterionId").asText())) {
                            excerpt = sourceEntry.path("excerpt").asText();
                            break;
                        }
                    }
                    mapping.put("designerExcerpt", excerpt);
                    mapping.put("verificationMode", criterion.path("verificationMode").asText("MACHINE"));
                    mapping.set("judgeRubric", criterion.path("judgeRubric"));
                    mapping.set("judgeOnlyReason", criterion.path("judgeOnlyReason"));
                    tools.jackson.databind.node.ArrayNode testCommand = mapper.createArrayNode();
                    tools.jackson.databind.node.ArrayNode testTargets = mapper.createArrayNode();
                    String strategy = "deterministic verifier";
                    for (tools.jackson.databind.JsonNode verifier : stage.path("verifiers")) {
                        boolean mapped = false;
                        for (tools.jackson.databind.JsonNode id : verifier.path("criterionIds")) {
                            if (criterionId.equals(id.asText())) mapped = true;
                        }
                        if (!mapped) continue;
                        strategy = verifier.path("type").asText("deterministic verifier");
                        if ("PROCESS".equals(verifier.path("type").asText())
                                && "TEST".equals(verifier.path("processPurpose").asText())) {
                            for (tools.jackson.databind.JsonNode value : verifier.path("command")) testCommand.add(value.asText());
                            for (tools.jackson.databind.JsonNode value : verifier.path("testTargets")) testTargets.add(value.asText());
                        }
                        break;
                    }
                    mapping.put("verifierStrategy", strategy);
                    mapping.set("testCommand", testCommand);
                    mapping.set("testTargets", testTargets);
                }
            }
            plan.set("stages", stagePlans);
            plan.set("evidenceMappings", evidenceMappings);
            plan.set("handoffSummary", source.path("handoffSummary"));
            plan.set("designGaps", source.path("designGaps").isArray()
                    ? source.path("designGaps") : mapper.createArrayNode());
            return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n" + mapper.writeValueAsString(plan)
                    + "\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
        } catch (Exception invalid) {
            return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n{}\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
        }
    }

    private tools.jackson.databind.node.ObjectNode markedObject(String output, String startMarker,
                                                                String endMarker, ObjectMapper mapper) throws Exception {
        if (output == null) throw new IllegalArgumentException("missing output");
        int markerStart = output.indexOf(startMarker);
        int markerEnd = output.indexOf(endMarker);
        if (markerStart < 0 || markerEnd <= markerStart) throw new IllegalArgumentException("missing markers");
        String body = output.substring(markerStart + startMarker.length(), markerEnd);
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("missing object");
        return (tools.jackson.databind.node.ObjectNode) mapper.readTree(body.substring(start, end + 1));
    }
}
