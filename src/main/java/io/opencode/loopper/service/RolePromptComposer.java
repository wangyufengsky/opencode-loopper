package io.opencode.loopper.service;

import io.opencode.loopper.domain.TestPolicy;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class RolePromptComposer {
    public String requirementDesignerInstructions(TaskProfileService.View profile) {
        String specialized = switch (profile.rolePackId()) {
            case "software-python" -> "Treat this as Python software work. Ask about invocation, input/output, error semantics, portability, and whether repository tests exist. Never introduce Java or Maven assumptions.";
            case "software-node" -> "Treat this as Node/Vue/TypeScript software work. Ask about runtime, package manager, UI/runtime boundary, and the repository's actual test command.";
            case "software-mixed" -> "Treat this as a mixed-stack vertical software change. Ask about the cross-stack behavior boundary and identify each repository-native focused test; do not force Java examples onto Python or Node packages.";
            case "software-generic" -> "Treat this as repository-native software work whose stack is not one of the built-in Java, Python, or Node families. Ask about its runtime, build tool, focused test command, and observable behavior. Never introduce Java or Maven assumptions.";
            case "document-markdown-docx" -> "Treat this as document authoring. Ask about target path/format, audience, required sections, tables, and unsupported images/embedded objects. Do not turn it into a code task.";
            case "tabular-conversion" -> "Treat this as a one-off bounded table conversion. Ask about the exact managed input and output paths, Sheet selection, formulas, merged cells, and empty-row policy. Do not propose a reusable script.";
            case "read-only-report" -> "Treat this as a read-only review/research report. Ask about review scope, severity or research criteria, and required evidence granularity. Do not propose edits or a Task.";
            case "local-maintenance" -> "Treat this as safe local maintenance. Ask for exact files and observable outcome. Explicitly exclude deletion, service control, Git submission/push/release, external applications, and external writes.";
            default -> "Treat this as Java software work only when repository and requirement evidence support Java; require observable behavior and a focused repository-native test.";
        };
        return header(profile) + "\n" + specialized
                + "\nThese are clarification topics only when the current phase permits questions. Ask only about "
                + "unresolved decisions; preserve prior answers. Task settings are confirmed by Loopper, not by "
                + "another classification question. Repository observations are evidence, not new user requirements.";
    }

    public String decomposerInstructions(TaskProfileService.View profile) {
        return header(profile) + "\n" + switch (profile.workflowTemplate()) {
            case PACKAGED_ARTIFACT -> "Decompose by document chapters or coherent audience-facing sections. Each package must produce a structured fragment that can be deterministically aggregated; never split by implementation layer.";
            case LOCAL_MAINTENANCE -> "Use one package for a simple bounded maintenance change. Complex maintenance may use vertical packages, but every package must preserve the no-delete/no-service/no-external-write boundary.";
            default -> switch (profile.rolePackId()) {
                case "software-python" -> "Decompose Python work by usable vertical behavior. A reusable conversion script is software, not a one-off tabular artifact.";
                case "software-node" -> "Decompose Node/frontend work by user-visible vertical capability, not by frontend/backend/test layers.";
                case "software-mixed" -> "Decompose mixed-stack work by end-to-end observable capability. Keep every stack needed by one business behavior together instead of splitting packages by language or layer.";
                case "software-generic" -> "Decompose by repository-native vertical behavior without assuming Java, Maven, Node, or Python tooling.";
                default -> "Decompose software by vertical observable capability and keep production behavior with its focused test.";
            };
        };
    }

    public String packageDesignerInstructions(TaskProfileService.View profile, String rolePackId,
                                              io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                                              List<String> technologies, TestPolicy testPolicy) {
        String focus = switch (rolePackId) {
            case "document-markdown-docx" -> "Design headings, paragraphs, lists and tables for one document fragment; include no executable test.";
            case "tabular-conversion" -> "Describe source/output equivalence, sheet selection and conversion rules; the server performs the conversion.";
            case "local-maintenance" -> "Design only the exact requested file changes. Forbid deletion, service control, Git publication and external writes in plain language.";
            case "software-java" -> "Keep Java production behavior and its focused Maven/Gradle test targets together in every stage, including wiring/demo changes.";
            case "software-python" -> "Use Python invocation, input/output and error semantics; identify repository pytest/unittest targets when required.";
            case "software-node" -> "Use the detected Node/Vue/TypeScript runtime and UI boundary, with repository-native test targets.";
            case "software-mixed" -> "Design vertical behavior across the frozen stacks; keep each affected stack's native tests with its production behavior.";
            default -> "Use the evidenced repository-native stack and test targets; never guess another stack's commands.";
        };
        return "Work-package Role Pack: " + rolePackId + "@" + profile.rolePackVersion()
                + "; technologies=" + technologies + "; execution=" + executionStrategy
                + "; testPolicy=" + testPolicy + ".\n" + focus
                + "\nDescribe test intent and exact evidenced target names, not argv, verifier JSON or compiler fields. "
                + "REQUIRED retains focused tests; OPTIONAL uses available native evidence; NOT_APPLICABLE uses "
                + "the artifact's structural/data evidence. State observable results separately from build/test success.";
    }

    public String reviewerInstructions(TaskProfileService.View profile) {
        return header(profile) + "\nAct as an independent read-only Reviewer. Use only read/glob/grep. Every concrete finding must cite a managed relative file path and exact line as path:line. Separate confirmed findings, limitations, and recommendations. Recommend concrete corrections for confirmed findings, but do not perform them or claim a Task was created. Distinguish a verified defect from uncertainty; report coverage limitations instead of inventing findings.";
    }

    public String implementationInstructions(String rolePackId, String rolePackVersion,
                                             List<String> technologies, TestPolicy testPolicy) {
        String id = rolePackId == null || rolePackId.isBlank() ? "legacy-software" : rolePackId;
        String version = rolePackVersion == null || rolePackVersion.isBlank() ? "legacy" : rolePackVersion;
        String specialized = switch (id) {
            case "software-java" -> "Use the repository Maven/Gradle conventions. Production Java changes require the focused TEST frozen in the verifier contract.";
            case "software-python" -> "Use Python repository conventions. Prefer pytest/unittest when tests are required; a standalone script without a test framework may use an authorized deterministic SELF_CHECK plus native artifact assertions.";
            case "software-node" -> "Use package.json scripts and the detected Node/Vue conventions. A required test must use a focused npm target and cannot be replaced by build output.";
            case "software-mixed" -> "Respect each frozen technology boundary. Use only its repository-native test framework and keep cross-stack business acceptance separate from build evidence.";
            case "software-generic" -> "Use only the frozen repository-native runtime and test conventions. Do not infer Java, Maven, Node, or Python commands from generic software wording.";
            case "local-maintenance" -> "Modify only the exact frozen files. Never delete files, control services, publish Git state, or write to external systems.";
            default -> "Follow the frozen verifier contract and repository conventions without assuming Java or Maven.";
        };
        return "Frozen execution Role Pack: " + id + "@" + version
                + "\nFrozen technologies: " + (technologies == null ? List.of() : technologies)
                + "\nFrozen test policy: " + (testPolicy == null ? TestPolicy.REQUIRED : testPolicy)
                + "\n" + specialized
                + "\nREQUIRED means execute the recognized focused TEST declared by the verifier contract. OPTIONAL permits TEST or an authorized SELF_CHECK. NOT_APPLICABLE forbids PROCESS TEST.";
    }

    public String compilerInstructions(RolePackRegistry.RolePack pack, List<String> technologies,
                                       TestPolicy testPolicy) {
        String stack = technologies == null || technologies.isEmpty() ? "未确定技术栈" : String.join("/", technologies);
        String testing = switch (testPolicy) {
            case REQUIRED -> requiredTestInstructions(pack.id());
            case OPTIONAL -> "优先使用仓库已有测试；无测试体系的独立脚本可用 SELF_CHECK 与原生制品验收。";
            case NOT_APPLICABLE -> "不得生成 PROCESS TEST；使用专属文档、表格或报告证据。";
        };
        return "Role Pack: " + pack.id() + "@" + pack.version() + "（" + pack.displayName() + "）。\n"
                + "技术/制品上下文：" + stack + "。\n" + testing;
    }

    public String compilerPlanningExample(String rolePackId) {
        return switch (rolePackId) {
            case "software-java" -> "{\"outcome\":\"COMPILED\",\"summary\":\"Java package plan\",\"stages\":[{\"objective\":\"observable Java result\",\"implementationKind\":\"JAVA_PRODUCTION\",\"allowedPaths\":[\"src/main/java/**\",\"src/test/java/**\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"implementation and focused test\"],\"criteria\":[{\"description\":\"observable business result\",\"sourceRefs\":[\"DS-L001\"],\"judgeRubric\":null,\"judgeOnlyReason\":null}],\"evidence\":[{\"kind\":\"FOCUSED_TEST\",\"command\":[\"mvn\",\"-q\",\"-Dtest=ExampleFocusedTest\",\"test\"],\"covers\":[0]}],\"verificationRuntime\":null}],\"handoffSummary\":\"bounded handoff\",\"designGaps\":[]}";
            case "software-python" -> "{\"outcome\":\"COMPILED\",\"summary\":\"Python package plan\",\"stages\":[{\"objective\":\"observable Python result\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"src/**\",\"tests/**\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"Python implementation\"],\"criteria\":[{\"description\":\"observable business result\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"FOCUSED_TEST\",\"command\":[\"python3\",\"-m\",\"pytest\",\"tests/test_example.py\"],\"covers\":[0]}],\"verificationRuntime\":null}],\"handoffSummary\":\"bounded handoff\",\"designGaps\":[]}";
            case "software-node" -> "{\"outcome\":\"COMPILED\",\"summary\":\"Node package plan\",\"stages\":[{\"objective\":\"observable Node or UI result\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"src/**\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"Node or UI implementation\"],\"criteria\":[{\"description\":\"observable business result\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"FOCUSED_TEST\",\"command\":[\"npm\",\"test\",\"--\",\"src/example.spec.ts\"],\"covers\":[0]}],\"verificationRuntime\":null}],\"handoffSummary\":\"bounded handoff\",\"designGaps\":[]}";
            case "software-mixed" -> "{\"outcome\":\"COMPILED\",\"summary\":\"mixed-stack vertical plan\",\"stages\":[{\"objective\":\"observable backend behavior for the vertical capability\",\"implementationKind\":\"JAVA_PRODUCTION\",\"allowedPaths\":[\"backend/**\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"backend behavior and focused test\"],\"criteria\":[{\"description\":\"observable backend business result\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"FOCUSED_TEST\",\"command\":[\"mvn\",\"-q\",\"-Dtest=ExampleFocusedTest\",\"test\"],\"covers\":[0]}],\"verificationRuntime\":null},{\"objective\":\"observable client behavior consuming the same vertical capability\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"frontend/**\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"client behavior and focused test\"],\"criteria\":[{\"description\":\"observable client business result\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"FOCUSED_TEST\",\"command\":[\"npm\",\"test\",\"--\",\"src/example.spec.ts\"],\"covers\":[0]}],\"verificationRuntime\":null}],\"handoffSummary\":\"bounded cross-stack handoff\",\"designGaps\":[]}";
            case "local-maintenance" -> "{\"outcome\":\"COMPILED\",\"summary\":\"bounded maintenance plan\",\"stages\":[{\"objective\":\"observable configuration result\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"config/example.yml\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"updated configuration\"],\"criteria\":[{\"description\":\"configuration has the frozen value\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"FILE_CONTENT\",\"path\":\"config/example.yml\",\"expectedContent\":\"enabled: true\\n\",\"covers\":[0]},{\"kind\":\"GIT_DIFF\",\"requireChanges\":true,\"allowedPaths\":[\"config/example.yml\"],\"forbidDeletes\":true,\"covers\":[]}],\"verificationRuntime\":null}],\"handoffSummary\":\"bounded maintenance handoff\",\"designGaps\":[]}";
            case "document-markdown-docx" -> "{\"outcome\":\"COMPILED\",\"summary\":\"document plan\",\"stages\":[{\"objective\":\"observable document structure\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"docs/output.md\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"document\"],\"criteria\":[{\"description\":\"required heading exists\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"DOCUMENT_STRUCTURE\",\"path\":\"docs/output.md\",\"documentAssertions\":[{\"type\":\"HEADING_EXISTS\",\"value\":\"Overview\"}],\"covers\":[0]}],\"verificationRuntime\":null}],\"handoffSummary\":\"document handoff\",\"designGaps\":[]}";
            case "tabular-conversion" -> "{\"outcome\":\"COMPILED\",\"summary\":\"tabular plan\",\"stages\":[{\"objective\":\"observable table equivalence\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"output.xlsx\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"converted table\"],\"criteria\":[{\"description\":\"output table matches the frozen source\",\"sourceRefs\":[\"DS-L001\"]}],\"evidence\":[{\"kind\":\"TABULAR_DATA\",\"path\":\"output.xlsx\",\"tabularAssertions\":[{\"type\":\"EQUIVALENT_TO\",\"sourcePath\":\"input.xlsx\"}],\"covers\":[0]}],\"verificationRuntime\":null}],\"handoffSummary\":\"tabular handoff\",\"designGaps\":[]}";
            case "read-only-report" -> "This Role Pack is completed by REVIEWER_READ_ONLY and must never enter LoopSpec Compiler.";
            default -> "{\"outcome\":\"COMPILED\",\"summary\":\"repository-native software plan\",\"stages\":[{\"objective\":\"observable repository-native result\",\"implementationKind\":\"NON_JAVA\",\"allowedPaths\":[\"src/output.txt\"],\"forbiddenPaths\":[\".env\"],\"deliverables\":[\"repository-native implementation\"],\"criteria\":[{\"description\":\"observable business result\",\"sourceRefs\":[\"DS-L001\"],\"judgeRubric\":\"Check the frozen observable result from implementation evidence\",\"judgeOnlyReason\":null}],\"evidence\":[{\"kind\":\"FILE_CONTENT\",\"path\":\"src/output.txt\",\"expectedContent\":\"expected observable output\",\"covers\":[0]},{\"kind\":\"GIT_DIFF\",\"requireChanges\":true,\"forbidDeletes\":true,\"covers\":[]}],\"verificationRuntime\":null}],\"handoffSummary\":\"bounded handoff\",\"designGaps\":[]}";
        };
    }

    private String requiredTestInstructions(String rolePackId) {
        return switch (rolePackId) {
            case "software-java" -> "每个 JAVA_PRODUCTION Stage 都必须使用安全的 Maven/Gradle 显式选择器给出聚焦 TEST；即使业务准则只由 Judge 判断也必须保留 covers:[] 的聚焦 TEST 作为 Java 门禁。不得创建只有 FULL_TEST/BUILD 的 Java 接线或演示 Stage；应把它合并到相关聚焦测试 Stage，且不得注入 Python 或 npm 示例。";
            case "software-python" -> "必须使用仓库识别出的 pytest 或 unittest 给出显式目标的聚焦 TEST；不得注入其他技术栈的测试示例。";
            case "software-node" -> "必须使用仓库识别出的 npm test 脚本和 `--` 后的显式目标；不得注入其他技术栈的测试示例。";
            case "software-mixed" -> "每个发生生产变更且已有识别测试框架的技术边界都必须使用自己的聚焦 TEST；每个 JAVA_PRODUCTION Stage 必须保留 Maven/Gradle 聚焦 TEST，Judge-only 时使用 covers:[]。不得创建只有 FULL_TEST/BUILD 的 Java 接线或演示 Stage；应合并到相关聚焦测试 Stage。同一业务 Stage 可以包含多个框架证据，禁止按语言机械拆 Stage。";
            case "software-generic" -> "必须使用冻结仓库实际识别出的测试框架和显式目标；未知技术栈不得猜测 Java、Maven、npm 或 pytest 命令。";
            default -> "必须按当前 Role Pack 的专属合同给出确定性行为证据，不得套用其他技术栈的测试示例。";
        };
    }

    public String compilerInstructions(String rolePackId, String rolePackVersion,
                                       io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                                       List<String> technologies, TestPolicy testPolicy) {
        return compilerInstructions(new RolePackRegistry.RolePack(rolePackId, rolePackVersion, rolePackId,
                executionStrategy, testPolicy), technologies, testPolicy);
    }

    public String compilerInstructions(TaskProfileService.View profile) {
        RolePackRegistry.RolePack pack = new RolePackRegistry.RolePack(profile.rolePackId(), profile.rolePackVersion(),
                profile.rolePackId(), profile.executionStrategy(), profile.testPolicy());
        return compilerInstructions(pack, profile.technologies(), profile.testPolicy());
    }

    private static String header(TaskProfileService.View profile) {
        return "Frozen Role Pack " + profile.rolePackId() + "@" + profile.rolePackVersion()
                + "; intent=" + profile.intent() + "; workflow=" + profile.workflowTemplate()
                + "; execution=" + profile.executionStrategy() + "; testPolicy=" + profile.testPolicy() + ".";
    }
}
