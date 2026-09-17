package io.opencode.loopper.service;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit historical fixture; these tests cover the frozen V4 text-transport recovery path. */
final class LegacyTemplateFixture {
    private LegacyTemplateFixture() { }
    static void freezeV4(JdbcTemplate jdbc, ObjectMapper json, String taskId) {
        ObjectNode contract = (ObjectNode) json.readTree(jdbc.queryForObject(
                "SELECT contract_json FROM template_task_run WHERE task_id=?", String.class, taskId));
        ((ObjectNode) contract.get("definition")).put("version", "4");
        contract.remove("batchMaxRetries");
        contract.set("reportTemplates", json.valueToTree(io.opencode.loopper.template.TemplateReportLayout.freeze()));
        jdbc.update("UPDATE template_task_run SET template_version='4',contract_json=? WHERE task_id=?", json.writeValueAsString(contract), taskId);
    }
}
