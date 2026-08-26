package io.opencode.loopper.runtime;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Closed JSON Schemas used by OpenCode format=json_schema. Semantic validation remains server-authoritative. */
public final class OpenCodeStructuredSchemas {
    public static final String DECOMPOSITION_PLAN_V1 = "DECOMPOSITION_PLAN_V1";
    public static final String DECOMPOSITION_SEMANTIC_V2 = "DECOMPOSITION_SEMANTIC_V2";
    public static final String DECOMPOSITION_FINAL_V1 = "DECOMPOSITION_FINAL_V1";
    public static final String PACKAGE_COMPILATION_PLAN_V2 = "PACKAGE_COMPILATION_PLAN_V2";
    public static final String PACKAGE_COMPILATION_SEMANTIC_V3 = "PACKAGE_COMPILATION_SEMANTIC_V3";
    public static final String PACKAGE_ACCEPTANCE_BINDING_V4 = "PACKAGE_ACCEPTANCE_BINDING_V4";
    public static final String PACKAGE_ACCEPTANCE_BINDING_V5 = "PACKAGE_ACCEPTANCE_BINDING_V5";
    public static final String PACKAGE_COMPILATION_FINAL_V2 = "PACKAGE_COMPILATION_FINAL_V2";
    public static final String AI_SEMANTIC_PATCH_V1 = "AI_SEMANTIC_PATCH_V1";
    public static final String JUDGE_DECISION_V1 = "JUDGE_DECISION_V1";
    public static final String TASK_PROFILE_ROUTER_V1 = "TASK_PROFILE_ROUTER_V1";
    public static final String TASK_PROFILE_ROUTER_V2 = "TASK_PROFILE_ROUTER_V2";
    public static final String REVIEWER_REPORT_V1 = "REVIEWER_REPORT_V1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenCodeStructuredSchemas() { }

    public static OpenCodeClient.ResponseFormat.JsonSchema format(String schemaId) {
        return new OpenCodeClient.ResponseFormat.JsonSchema(schemaId, schema(schemaId), 0);
    }

    public static Map<String, Object> schema(String schemaId) {
        return switch (schemaId) {
            case DECOMPOSITION_PLAN_V1 -> read(DECOMPOSITION_PLAN);
            case DECOMPOSITION_SEMANTIC_V2 -> read(DECOMPOSITION_SEMANTIC);
            case DECOMPOSITION_FINAL_V1 -> read(DECOMPOSITION_FINAL);
            case PACKAGE_COMPILATION_PLAN_V2 -> read(PACKAGE_COMPILATION_PLAN);
            case PACKAGE_COMPILATION_SEMANTIC_V3 -> read(PACKAGE_COMPILATION_SEMANTIC);
            case PACKAGE_ACCEPTANCE_BINDING_V4 -> read(PACKAGE_ACCEPTANCE_BINDING_V4_SCHEMA);
            case PACKAGE_ACCEPTANCE_BINDING_V5 -> read(PACKAGE_ACCEPTANCE_BINDING_V5_SCHEMA);
            case PACKAGE_COMPILATION_FINAL_V2 -> read(PACKAGE_COMPILATION_FINAL);
            case AI_SEMANTIC_PATCH_V1 -> read(AI_SEMANTIC_PATCH);
            case JUDGE_DECISION_V1 -> read(JUDGE_DECISION);
            case TASK_PROFILE_ROUTER_V1 -> read(TASK_PROFILE_ROUTER_V1_SCHEMA);
            case TASK_PROFILE_ROUTER_V2 -> read(TASK_PROFILE_ROUTER_V2_SCHEMA);
            case REVIEWER_REPORT_V1 -> read(REVIEWER_REPORT);
            default -> throw new IllegalArgumentException("Unknown OpenCode response schema: " + schemaId);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static final String DECOMPOSITION_DEFS = """
        "$defs": {
          "constraint": {"type":"object","additionalProperties":false,"required":["text","requirementRefs"],"properties":{"text":{"type":"string","minLength":1,"maxLength":2000},"requirementRefs":{"type":"array","maxItems":128,"items":{"type":"string","minLength":1,"maxLength":64}}}},
          "workPackage": {"type":"object","additionalProperties":false,"required":["id","title","objective","scopeIn","scopeOut","dependencies","deliverables","acceptanceIntent","requirementRefs"],"properties":{"id":{"type":"string","pattern":"^WP-[1-6]$"},"title":{"type":"string","minLength":1,"maxLength":200},"objective":{"type":"string","minLength":1,"maxLength":2000},"scopeIn":{"type":"array","maxItems":64,"items":{"type":"string","minLength":1,"maxLength":1000}},"scopeOut":{"type":"array","maxItems":64,"items":{"type":"string","minLength":1,"maxLength":1000}},"dependencies":{"type":"array","maxItems":5,"items":{"type":"string","pattern":"^WP-[1-6]$"}},"deliverables":{"type":"array","maxItems":64,"items":{"type":"string","minLength":1,"maxLength":2000}},"acceptanceIntent":{"type":"array","maxItems":64,"items":{"type":"string","minLength":1,"maxLength":2000}},"requirementRefs":{"type":"array","maxItems":128,"items":{"type":"string","minLength":1,"maxLength":64}}}},
          "gap": {"type":"object","additionalProperties":false,"required":["code","detail"],"properties":{"code":{"type":"string","enum":["MISSING_OBSERVABLE_OUTCOME","MISSING_EXCEPTION_SEMANTICS","MISSING_SCOPE","MISSING_ACCEPTANCE_INTENT","LARGE_TASK_MODE_REQUIRED"]},"detail":{"type":"string","minLength":1,"maxLength":2000}}}
        }
        """;

    private static final String DECOMPOSITION_PLAN = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,
          "required":["status","normalizedGoal","globalConstraints","workPackages","coverageMappings","dependencyEvidence","designGaps","reason"],
          "properties":{
            "status":{"type":"string","enum":["DIRECT_DESIGN","DECOMPOSED","NEEDS_INPUT","MULTI_TASK_REQUIRED"]},
            "normalizedGoal":{"type":"string","minLength":1,"maxLength":12000},
            "globalConstraints":{"type":"array","maxItems":64,"items":{"$ref":"#/$defs/constraint"}},
            "workPackages":{"type":"array","maxItems":6,"items":{"$ref":"#/$defs/workPackage"}},
            "coverageMappings":{"type":"array","maxItems":256,"items":{"type":"object","additionalProperties":false,"required":["requirementRef","targetType","targetId","rationale"],"properties":{"requirementRef":{"type":"string","minLength":1,"maxLength":64},"targetType":{"type":"string","enum":["GLOBAL_CONSTRAINT","WORK_PACKAGE"]},"targetId":{"type":"string","minLength":1,"maxLength":64},"rationale":{"type":"string","minLength":1,"maxLength":2000}}}},
            "dependencyEvidence":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["workPackageId","dependsOn","rationale"],"properties":{"workPackageId":{"type":"string","pattern":"^WP-[1-6]$"},"dependsOn":{"type":"string","pattern":"^WP-[1-6]$"},"rationale":{"type":"string","minLength":1,"maxLength":2000}}}},
            "designGaps":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/gap"}},
            "reason":{"type":["string","null"],"maxLength":4000}
          },
          %s
        }
        """.formatted(DECOMPOSITION_DEFS);

    private static final String DECOMPOSITION_FINAL = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,
          "required":["status","normalizedGoal","globalConstraints","workPackages","designGaps","reason"],
          "properties":{
            "status":{"type":"string","enum":["DIRECT_DESIGN","DECOMPOSED","NEEDS_INPUT","MULTI_TASK_REQUIRED"]},
            "normalizedGoal":{"type":"string","minLength":1,"maxLength":12000},
            "globalConstraints":{"type":"array","maxItems":64,"items":{"$ref":"#/$defs/constraint"}},
            "workPackages":{"type":"array","maxItems":6,"items":{"$ref":"#/$defs/workPackage"}},
            "designGaps":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/gap"}},
            "reason":{"type":["string","null"],"maxLength":4000}
          },
          %s
        }
        """.formatted(DECOMPOSITION_DEFS);

    private static final String DECOMPOSITION_SEMANTIC = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":true,
          "required":["outcome","normalizedGoal","globalConstraints","workPackages","coverage"],
          "properties":{
            "outcome":{"type":"string","enum":["READY","NEEDS_INPUT","MULTI_TASK_REQUIRED"]},
            "normalizedGoal":{"type":"string","minLength":1,"maxLength":12000},
            "globalConstraints":{"type":"array","maxItems":64,"items":{"type":"object","required":["text"],"properties":{"text":{"type":"string","minLength":1,"maxLength":2000}}}},
            "workPackages":{"type":"array","maxItems":6,"items":{"type":"object","required":["title","objective","scopeIn","scopeOut","deliverables","acceptanceIntent","dependsOn"],"properties":{"title":{"type":"string","minLength":1,"maxLength":200},"objective":{"type":"string","minLength":1,"maxLength":2000},"scopeIn":{"type":"array","items":{"type":"string"}},"scopeOut":{"type":"array","items":{"type":"string"}},"deliverables":{"type":"array","items":{"type":"string"}},"acceptanceIntent":{"type":"array","items":{"type":"string"}},"dependsOn":{"type":"array","items":{"anyOf":[{"type":"integer"},{"type":"object"}]}}}}},
            "coverage":{"type":"array","maxItems":256,"items":{"type":"object","required":["requirementRef","targetType","targetIndex"],"properties":{"requirementRef":{"type":"string"},"targetType":{"type":"string","enum":["GLOBAL_CONSTRAINT","WORK_PACKAGE"]},"targetIndex":{"type":"integer","minimum":0},"rationale":{"type":["string","null"]}}}},
            "designGaps":{"type":"array"},"reason":{"type":["string","null"]}
          }
        }
        """;

    private static final String COMPILER_DEFS = """
        "$defs": {
          "stringList":{"type":"array","maxItems":64,"items":{"type":"string","minLength":1,"maxLength":2048}},
          "gap":{"type":"object","additionalProperties":false,"required":["code","detail"],"properties":{"code":{"type":"string","enum":["MISSING_OBSERVABLE_OUTCOME","MISSING_EXCEPTION_SEMANTICS","MISSING_SCOPE","MISSING_ACCEPTANCE_INTENT","AMBIGUOUS_ACCEPTANCE_INTENT","VERIFICATION_CAPABILITY_UNAVAILABLE","LARGE_TASK_MODE_REQUIRED"]},"detail":{"type":"string","minLength":1,"maxLength":2000}}},
          "assertion":{"type":"object","additionalProperties":false,"required":["type","selector","value","attribute","expectedCount"],"properties":{"type":{"type":"string","minLength":1,"maxLength":64},"selector":{"type":"string","minLength":1,"maxLength":1024},"value":{"type":["string","null"],"maxLength":4000},"attribute":{"type":["string","null"],"maxLength":256},"expectedCount":{"type":["integer","null"],"minimum":0}}},
          "documentAssertion":{"type":"object","additionalProperties":false,"required":["type"],"properties":{"type":{"type":"string","enum":["HEADING_EXISTS","TEXT_EXISTS","TABLE_COUNT","LOCAL_LINKS_VALID"]},"value":{"type":["string","null"],"maxLength":2000},"expectedCount":{"type":["integer","null"],"minimum":0,"maximum":10000},"headingLevel":{"type":["integer","null"],"minimum":1,"maximum":4}}},
          "tabularAssertion":{"type":"object","additionalProperties":false,"required":["type"],"properties":{"type":{"type":"string","enum":["SHEET_EXISTS","ROW_COUNT","COLUMN_COUNT","HEADER_EQUALS","CELL_EQUALS","EQUIVALENT_TO"]},"sheet":{"type":["string","null"],"maxLength":128},"row":{"type":["integer","null"],"minimum":0,"maximum":100000},"column":{"type":["integer","null"],"minimum":0,"maximum":1000},"expectedValue":{"type":["string","null"],"maxLength":4000},"expectedCount":{"type":["integer","null"],"minimum":0,"maximum":100000},"sourcePath":{"type":["string","null"],"maxLength":512}}},
          "verifier":{"type":"object","additionalProperties":false,"required":["type"],"properties":{"type":{"type":"string","enum":["PROCESS","FILE_EXISTS","FILE_NOT_EXISTS","GIT_DIFF","HTTP_STATUS","JSON_PATH","FILE_CONTENT","FILE_HASH","JUNIT_XML","BROWSER","DATABASE_QUERY","DOCUMENT_STRUCTURE","TABULAR_DATA"]},"command":{"$ref":"#/$defs/stringList"},"path":{"type":["string","null"],"maxLength":2048},"requireChanges":{"type":["boolean","null"]},"allowedPaths":{"$ref":"#/$defs/stringList"},"forbiddenPaths":{"$ref":"#/$defs/stringList"},"forbidDeletes":{"type":["boolean","null"]},"outputContains":{"type":["string","null"],"maxLength":4000},"url":{"type":["string","null"],"maxLength":2048},"httpMethod":{"type":["string","null"],"enum":["GET","POST","PUT","PATCH","DELETE",null]},"expectedStatus":{"type":["integer","null"],"minimum":100,"maximum":599},"jsonPath":{"type":["string","null"],"maxLength":1024},"expectedValue":{"type":["string","null"],"maxLength":4000},"matchMode":{"type":["string","null"],"maxLength":32},"expectedContent":{"type":["string","null"],"maxLength":4000},"expectedSha256":{"type":["string","null"],"pattern":"^[0-9a-fA-F]{64}$"},"sql":{"type":["string","null"],"maxLength":16000},"expectedRowCount":{"type":["integer","null"],"minimum":0},"assertions":{"type":"array","maxItems":64,"items":{"$ref":"#/$defs/assertion"}},"criterionIds":{"$ref":"#/$defs/stringList"},"processPurpose":{"type":["string","null"],"enum":["BUILD","TEST","SELF_CHECK",null]},"testTargets":{"$ref":"#/$defs/stringList"},"documentAssertions":{"type":"array","maxItems":64,"items":{"$ref":"#/$defs/documentAssertion"}},"tabularAssertions":{"type":"array","maxItems":64,"items":{"$ref":"#/$defs/tabularAssertion"}}}},
          "readiness":{"type":"object","additionalProperties":false,"required":["path","expectedStatus","jsonPath","expectedValue","matchMode"],"properties":{"path":{"type":"string","minLength":1,"maxLength":1024},"expectedStatus":{"type":"integer","minimum":100,"maximum":599},"jsonPath":{"type":["string","null"],"maxLength":1024},"expectedValue":{"type":["string","null"],"maxLength":4000},"matchMode":{"type":["string","null"],"maxLength":32}}},
          "runtime":{"type":"object","additionalProperties":false,"required":["startCommand","readiness","startupTimeoutSeconds","shutdownTimeoutSeconds"],"properties":{"startCommand":{"$ref":"#/$defs/stringList"},"readiness":{"$ref":"#/$defs/readiness"},"startupTimeoutSeconds":{"type":"integer","minimum":1,"maximum":300},"shutdownTimeoutSeconds":{"type":"integer","minimum":1,"maximum":60}}},
          "criterion":{"type":"object","additionalProperties":false,"required":["id","description","verificationMode","judgeRubric","judgeOnlyReason"],"properties":{"id":{"type":"string","minLength":1,"maxLength":64},"description":{"type":"string","minLength":1,"maxLength":2000},"verificationMode":{"type":"string","enum":["MACHINE","JUDGE","BOTH"]},"judgeRubric":{"type":["string","null"],"maxLength":4000},"judgeOnlyReason":{"type":["string","null"],"maxLength":2000}}}
        }
        """;

    private static final String PACKAGE_COMPILATION_PLAN = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
          "required":["contractVersion","status","summary","stages","evidenceMappings","handoffSummary","designGaps"],
          "properties":{
            "contractVersion":{"type":"integer","const":2},
            "status":{"type":"string","enum":["COMPILED","DESIGN_INCOMPLETE"]},
            "summary":{"type":["string","null"],"maxLength":1000},
            "stages":{"type":"array","maxItems":6,"items":{"type":"object","additionalProperties":false,"required":["objective","allowedPaths","forbiddenPaths","deliverables","verifiers","verificationRuntime","implementationKind","workPackageId"],"properties":{"objective":{"type":"string","minLength":1,"maxLength":4000},"allowedPaths":{"$ref":"#/$defs/stringList"},"forbiddenPaths":{"$ref":"#/$defs/stringList"},"deliverables":{"$ref":"#/$defs/stringList"},"verifiers":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/verifier"}},"verificationRuntime":{"anyOf":[{"$ref":"#/$defs/runtime"},{"type":"null"}]},"implementationKind":{"type":"string","enum":["JAVA_PRODUCTION","JAVA_TEST_ONLY","NON_JAVA"]},"workPackageId":{"type":"string","pattern":"^WP-[1-6]$"}}}},
            "evidenceMappings":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["stageIndex","criterionId","description","designerExcerpt","verificationMode","judgeRubric","judgeOnlyReason","verifierStrategy","testCommand","testTargets"],"properties":{"stageIndex":{"type":"integer","minimum":0,"maximum":5},"criterionId":{"type":"string","minLength":1,"maxLength":64},"description":{"type":"string","minLength":1,"maxLength":2000},"designerExcerpt":{"type":"string","minLength":1,"maxLength":4000},"verificationMode":{"type":"string","enum":["MACHINE","JUDGE","BOTH"]},"judgeRubric":{"type":["string","null"],"maxLength":4000},"judgeOnlyReason":{"type":["string","null"],"maxLength":2000},"verifierStrategy":{"type":"string","minLength":1,"maxLength":2000},"testCommand":{"$ref":"#/$defs/stringList"},"testTargets":{"$ref":"#/$defs/stringList"}}}},
            "handoffSummary":{"type":["string","null"],"maxLength":4096},
            "designGaps":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/gap"}}
          },
          %s
        }
        """.formatted(COMPILER_DEFS);

    private static final String PACKAGE_COMPILATION_FINAL = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
          "required":["status","summary","stages","criterionSources","handoffSummary","designGaps"],
          "properties":{
            "status":{"type":"string","enum":["COMPILED","DESIGN_INCOMPLETE"]},
            "summary":{"type":["string","null"],"maxLength":1000},
            "stages":{"type":"array","maxItems":6,"items":{"type":"object","additionalProperties":false,"required":["objective","allowedPaths","forbiddenPaths","deliverables","verifiers","acceptanceCriteria","verificationRuntime","implementationKind","workPackageId"],"properties":{"objective":{"type":"string","minLength":1,"maxLength":4000},"allowedPaths":{"$ref":"#/$defs/stringList"},"forbiddenPaths":{"$ref":"#/$defs/stringList"},"deliverables":{"$ref":"#/$defs/stringList"},"verifiers":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/verifier"}},"acceptanceCriteria":{"type":"array","maxItems":64,"items":{"$ref":"#/$defs/criterion"}},"verificationRuntime":{"anyOf":[{"$ref":"#/$defs/runtime"},{"type":"null"}]},"implementationKind":{"type":"string","enum":["JAVA_PRODUCTION","JAVA_TEST_ONLY","NON_JAVA"]},"workPackageId":{"type":"string","pattern":"^WP-[1-6]$"}}}},
            "criterionSources":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,"required":["stageIndex","criterionId","excerpt"],"properties":{"stageIndex":{"type":"integer","minimum":0,"maximum":5},"criterionId":{"type":"string","minLength":1,"maxLength":64},"excerpt":{"type":"string","minLength":1,"maxLength":4000}}}},
            "handoffSummary":{"type":["string","null"],"maxLength":4096},
            "designGaps":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/gap"}}
          },
          %s
        }
        """.formatted(COMPILER_DEFS);

    private static final String PACKAGE_COMPILATION_SEMANTIC = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":true,
          "required":["outcome","stages","handoffSummary"],
          "properties":{
            "outcome":{"type":"string","enum":["COMPILED","DESIGN_INCOMPLETE"]},
            "summary":{"type":["string","null"],"maxLength":1000},
            "stages":{"type":"array","maxItems":6,"items":{"type":"object","required":["objective","implementationKind","allowedPaths","forbiddenPaths","deliverables","criteria","evidence"],"properties":{"objective":{"type":"string"},"implementationKind":{"type":"string","enum":["JAVA_PRODUCTION","JAVA_TEST_ONLY","NON_JAVA"]},"allowedPaths":{"type":"array","items":{"type":"string"}},"forbiddenPaths":{"type":"array","items":{"type":"string"}},"deliverables":{"type":"array","items":{"type":"string"}},"criteria":{"type":"array","items":{"type":"object","required":["description","sourceRefs"],"properties":{"description":{"type":"string"},"sourceRefs":{"type":"array","items":{"type":"string","pattern":"^DS-L[0-9]{3,}$"}},"judgeRubric":{"type":["string","null"]},"judgeOnlyReason":{"type":["string","null"]}}}},"evidence":{"type":"array","items":{"type":"object","required":["kind","covers"],"additionalProperties":true,"properties":{"kind":{"type":"string"},"command":{"type":"array","items":{"type":"string"}},"covers":{"type":"array","items":{"type":"integer","minimum":0}},"successMarker":{"type":["string","null"]}}}}}}},
            "handoffSummary":{"type":["string","null"],"maxLength":4096},"designGaps":{"type":"array"}
          }
        }
        """;

    private static final String PACKAGE_ACCEPTANCE_BINDING_V4_SCHEMA = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
          "required":["outcome","summary","groupHints","capabilityPreferences","handoffSummary","designGaps"],
          "properties":{
            "outcome":{"type":"string","enum":["COMPILED","DESIGN_INCOMPLETE"]},
            "summary":{"type":["string","null"],"maxLength":1000},
            "groupHints":{"type":"array","maxItems":6,"items":{"type":"object","additionalProperties":false,
              "required":["title","objective","factIndexes","dependsOnHintIndexes"],"properties":{
                "title":{"type":"string","minLength":1,"maxLength":200},
                "objective":{"type":"string","minLength":1,"maxLength":2000},
                "factIndexes":{"type":"array","maxItems":128,"items":{"type":"integer","minimum":0,"maximum":127}},
                "dependsOnHintIndexes":{"type":"array","maxItems":5,"items":{"type":"integer","minimum":0,"maximum":5}}
              }}},
            "capabilityPreferences":{"type":"array","maxItems":128,"items":{"type":"object","additionalProperties":false,
              "required":["factIndex","capabilityIndexes"],"properties":{
                "factIndex":{"type":"integer","minimum":0,"maximum":127},
                "capabilityIndexes":{"type":"array","maxItems":64,"items":{"type":"integer","minimum":0,"maximum":255}}
              }}},
            "handoffSummary":{"type":["string","null"],"maxLength":4096},
            "designGaps":{"type":"array","maxItems":32,"items":{"$ref":"#/$defs/gap"}}
          },
          %s
        }
        """.formatted(COMPILER_DEFS);

    private static final String PACKAGE_ACCEPTANCE_BINDING_V5_SCHEMA = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
          "required":["summary","groupHints","capabilityPreferences","handoffSummary"],
          "properties":{
            "summary":{"type":["string","null"],"maxLength":1000},
            "groupHints":{"type":"array","maxItems":6,"items":{"type":"object","additionalProperties":false,
              "required":["title","objective","factIndexes","dependsOnHintIndexes"],"properties":{
                "title":{"type":"string","minLength":1,"maxLength":200},
                "objective":{"type":"string","minLength":1,"maxLength":2000},
                "factIndexes":{"type":"array","maxItems":128,"items":{"type":"integer","minimum":0,"maximum":127}},
                "dependsOnHintIndexes":{"type":"array","maxItems":5,"items":{"type":"integer","minimum":0,"maximum":5}}
              }}},
            "capabilityPreferences":{"type":"array","maxItems":128,"items":{"type":"object","additionalProperties":false,
              "required":["factIndex","capabilityIndexes"],"properties":{
                "factIndex":{"type":"integer","minimum":0,"maximum":127},
                "capabilityIndexes":{"type":"array","maxItems":64,"items":{"type":"integer","minimum":0,"maximum":255}}
              }}},
            "handoffSummary":{"type":["string","null"],"maxLength":4096}
          }
        }
        """;

    private static final String AI_SEMANTIC_PATCH = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,"required":["patches"],
          "properties":{"patches":{"type":"array","minItems":1,"maxItems":16,"items":{
            "type":"object","additionalProperties":false,"required":["op","path"],
            "properties":{"op":{"type":"string","enum":["add","replace","remove"]},
              "path":{"type":"string","pattern":"^/"},"value":{}}
          }}}
        }
        """;

    private static final String JUDGE_DECISION = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,"required":["verdict","reason"],
          "properties":{"verdict":{"type":"string","enum":["PASS","REVISE","BLOCKED"]},"reason":{"type":"string","minLength":1,"maxLength":12000}}
        }
        """;

    private static final String TASK_PROFILE_ROUTER_V1_SCHEMA = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,
          "required":["intent","artifactKinds","technologies","complexity","confidence","signals"],
          "properties":{
            "intent":{"type":"string","enum":["SOFTWARE_CHANGE","DOCUMENT_AUTHORING","DATA_CONVERSION","READ_ONLY_REVIEW","RESEARCH","CONFIGURATION","LOCAL_MAINTENANCE"]},
            "artifactKinds":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string","enum":["SOURCE_CODE","PYTHON_SCRIPT","MARKDOWN","DOCX","XLSX","CSV","TSV","CONFIGURATION","ANALYSIS_REPORT","OTHER"]}},
            "technologies":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":64}},
            "complexity":{"type":"string","enum":["SIMPLE","PACKAGED"]},
            "confidence":{"type":"integer","minimum":0,"maximum":100},
            "signals":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":256}}
          }
        }
        """;

    private static final String TASK_PROFILE_ROUTER_V2_SCHEMA = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,
          "required":["intent","artifactKinds","complexity"],
          "properties":{
            "intent":{"type":"string","enum":["SOFTWARE_CHANGE","DOCUMENT_AUTHORING","DATA_CONVERSION","READ_ONLY_REVIEW","RESEARCH","CONFIGURATION","LOCAL_MAINTENANCE"]},
            "artifactKinds":{"type":"array","minItems":1,"maxItems":1,"items":{"type":"string","enum":["SOURCE_CODE","PYTHON_SCRIPT","MARKDOWN","DOCX","XLSX","CSV","TSV","CONFIGURATION","ANALYSIS_REPORT","OTHER"]}},
            "complexity":{"type":"string","enum":["SIMPLE","PACKAGED"]}
          }
        }
        """;

    private static final String REVIEWER_REPORT = """
        {
          "$schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object","additionalProperties":false,
          "required":["title","summary","findings","limitations"],
          "properties":{
            "title":{"type":"string","minLength":1,"maxLength":200},
            "summary":{"type":"string","minLength":1,"maxLength":8000},
            "findings":{"type":"array","minItems":1,"maxItems":128,"items":{
              "type":"object","additionalProperties":false,
              "required":["severity","title","detail","path","line","recommendation"],
              "properties":{
                "severity":{"type":"string","enum":["CRITICAL","HIGH","MEDIUM","LOW","INFO"]},
                "title":{"type":"string","minLength":1,"maxLength":300},
                "detail":{"type":"string","minLength":1,"maxLength":4000},
                "path":{"type":"string","minLength":1,"maxLength":1024},
                "line":{"type":"integer","minimum":1,"maximum":10000000},
                "recommendation":{"type":"string","minLength":1,"maxLength":4000}
              }
            }},
            "limitations":{"type":"array","maxItems":32,"items":{"type":"string","minLength":1,"maxLength":2000}}
          }
        }
        """;
}
