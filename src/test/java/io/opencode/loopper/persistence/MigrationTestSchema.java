package io.opencode.loopper.persistence;

/** Explicit latest schema expectation shared only by tests that intentionally upgrade to the current release. */
final class MigrationTestSchema {
    static final String LATEST = "190";
    private MigrationTestSchema() { }
    static int since(int previous) { return Integer.parseInt(LATEST) - previous; }
    static String withoutWorkflowLeaseOwner(String key,String actual) {
        if (!key.equals("table:workspace_lease")) return actual;
        String addition=", holder_workflow_attempt_id TEXT REFERENCES workflow_node_attempt(id)";
        org.assertj.core.api.Assertions.assertThat(actual).containsOnlyOnce(addition);
        return actual.replace(addition,"").replace(", holder_writeback_id TEXT REFERENCES workflow_writeback(id)","");
    }
}
