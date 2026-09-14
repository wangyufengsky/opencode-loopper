package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import io.opencode.loopper.domain.MachineCandidateKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class AllRoleModelProbeTest {
    @ParameterizedTest @EnumSource(value = MachineCandidateKind.class, names = {"DOCUMENT_REQUIREMENTS_V1", "DOCUMENT_REQUIREMENT_REVIEW_V1", "REQUIREMENT_CODE_ASSESSMENT_V1", "REQUIREMENT_ASSESSMENT_REVIEW_V1", "DOCUMENT_CODE_ASSESSMENT_V2", "DOCUMENT_CODE_REVIEW_V2"}, mode = EnumSource.Mode.EXCLUDE)
    void realCompilerFixtureAcceptsAndRepairableShapeFaultCanBeCorrected(MachineCandidateKind kind) {
        var json = new ObjectMapper();
        var probe = new AllRoleModelProbe(kind);
        var candidate = (tools.jackson.databind.node.ObjectNode) json.readTree(valid(kind));
        var valid = probe.evaluate(candidate.toString());
        assertThat(valid.accepted()).as("%s: %s", kind, valid.problems()).isTrue();
        candidate.put("note", "qualification");
        var rejected = probe.evaluate(candidate.toString());
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.retryable()).as("%s: %s", kind, rejected.problems()).isTrue();
        assertThat(rejected.problems()).extracting(MachineCandidateSubmission.Problem::pointer).contains("/note");
        candidate.remove("note");
        var repaired = probe.evaluate(candidate.toString());
        assertThat(repaired.accepted()).isTrue();
        if (kind == MachineCandidateKind.JUDGE_DECISION_V1) {
            assertThat(json.readTree(repaired.canonicalCandidateJson()).path("verdict").asText()).isEqualTo("BLOCKED");
        }
    }

    private String valid(MachineCandidateKind kind) {
        return switch (kind) {
            case DOCUMENT_REQUIREMENTS_V1, DOCUMENT_REQUIREMENT_REVIEW_V1,
                    REQUIREMENT_CODE_ASSESSMENT_V1, REQUIREMENT_ASSESSMENT_REVIEW_V1, DOCUMENT_CODE_ASSESSMENT_V2, DOCUMENT_CODE_REVIEW_V2 ->
                    throw new IllegalArgumentException("Document template roles use separate scoped gold fixtures");
            case DECOMPOSITION_PLAN_V2 -> """
                {
                  "outcome": "READY",
                  "normalizedGoal": "deliver an auditable correction loop",
                  "globalConstraints": [
                    {
                      "text": "remain loopback-only"
                    }
                  ],
                  "workPackages": [
                    {
                      "title": "Candidate validation",
                      "objective": "validate candidate semantics",
                      "scopeIn": [
                        "policy"
                      ],
                      "scopeOut": [],
                      "deliverables": [
                        "validated plan"
                      ],
                      "acceptanceIntent": [
                        "invalid candidates explain all problems"
                      ],
                      "dependsOn": []
                    },
                    {
                      "title": "Accepted plan persistence",
                      "objective": "persist only accepted plans",
                      "scopeIn": [
                        "writer"
                      ],
                      "scopeOut": [],
                      "deliverables": [
                        "authoritative snapshot"
                      ],
                      "acceptanceIntent": [
                        "accepted state is atomic"
                      ],
                      "dependsOn": [
                        {
                          "packageIndex": 0,
                          "rationale": "uses the validated plan"
                        }
                      ]
                    }
                  ],
                  "coverage": [
                    {
                      "requirementRef": "RQ-1",
                      "targetType": "GLOBAL_CONSTRAINT",
                      "targetIndex": 0,
                      "rationale": "network boundary"
                    },
                    {
                      "requirementRef": "RQ-2",
                      "targetType": "WORK_PACKAGE",
                      "targetIndex": 0,
                      "rationale": "validation behavior"
                    },
                    {
                      "requirementRef": "RQ-3",
                      "targetType": "WORK_PACKAGE",
                      "targetIndex": 1,
                      "rationale": "persistence behavior"
                    }
                  ],
                  "designGaps": [],
                  "reason": null
                }
                """;
            case PACKAGE_DESIGN_V1 -> """
                {
                  "contractVersion": "PACKAGE_DESIGN_V1",
                  "outcome": "READY",
                  "requirements": [
                    {
                      "key": "REQ-1",
                      "statement": "事件分发必须安全处理未注册事件"
                    }
                  ],
                  "scenarios": [
                    {
                      "key": "SC-1",
                      "title": "未注册事件被安全忽略",
                      "precondition": "事件类型尚未注册",
                      "action": "发布该事件",
                      "observableResult": "发布调用正常返回且没有处理器被调用",
                      "invariant": "既有已注册事件分发不变",
                      "requirementRefs": [
                        "REQ-1"
                      ]
                    }
                  ],
                  "deliverables": [
                    {
                      "key": "DEL-1",
                      "kind": "DELIVERABLE",
                      "target": "src/test/java/example/EventBusTest.java",
                      "description": "新增 EventBusTest 聚焦验证未注册事件分支",
                      "requirementRefs": [
                        "REQ-1"
                      ]
                    }
                  ],
                  "reviews": [],
                  "stages": [
                    {
                      "key": "STAGE-1",
                      "title": "事件分发测试",
                      "objective": "实现并验证未注册事件分支",
                      "includes": [
                        "SC-1",
                        "DEL-1"
                      ],
                      "dependencies": []
                    }
                  ],
                  "gapCodes": []
                }
                """;
            case ROLLING_PACKAGE_PLAN_V1 -> """
                {
                  "packages": [
                    {
                      "packageKey": "WP-2A",
                      "title": "拆分入口",
                      "objective": "实现入口",
                      "replaces": [
                        "WP-2"
                      ],
                      "dependencies": [
                        "WP-1"
                      ],
                      "requirementRefs": [
                        "RQ-2"
                      ]
                    },
                    {
                      "packageKey": "WP-2B",
                      "title": "拆分收口",
                      "objective": "完成收口",
                      "replaces": [
                        "WP-2"
                      ],
                      "dependencies": [
                        "WP-2A"
                      ],
                      "requirementRefs": [
                        "RQ-2"
                      ]
                    },
                    {
                      "packageKey": "WP-3X",
                      "title": "合并验证",
                      "objective": "合并剩余验证",
                      "replaces": [
                        "WP-2",
                        "WP-3"
                      ],
                      "dependencies": [
                        "WP-2B"
                      ],
                      "requirementRefs": [
                        "RQ-2",
                        "RQ-3"
                      ]
                    }
                  ]
                }
                """;
            case REVIEWER_REPORT_V1 -> """
                {
                  "title": "审查结果",
                  "summary": "无确认缺陷",
                  "findings": [],
                  "limitations": []
                }
                """;
            case PROJECT_CONVENTION_V1 -> """
                {
                  "contractVersion": "PROJECT_CONVENTION_V1",
                  "componentKeys": [
                    "java-root"
                  ],
                  "commandIds": [
                    "java-root:test"
                  ],
                  "pathIds": [
                    "java-root:root"
                  ]
                }
                """;
            case JUDGE_DECISION_V1 -> """
                {
                  "contractVersion": "JUDGE_DECISION_V1",
                  "role": "RISK",
                  "verdict": "BLOCKED",
                  "reason": "风险未解决",
                  "evidenceIds": [
                    "risk"
                  ]
                }
                """;
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> """
                {
                  "factAssignments": [],
                  "capabilityPreferences": [
                    {
                      "factIndex": 0,
                      "capabilityIndexes": [
                        0
                      ]
                    }
                  ]
                }
                """;
        };
    }
}
