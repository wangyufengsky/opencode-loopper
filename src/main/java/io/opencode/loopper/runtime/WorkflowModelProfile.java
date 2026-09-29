package io.opencode.loopper.runtime;

import java.util.List;

/** Server-owned protocol for a configurable node; business roles supply separate professional instructions. */
public final class WorkflowModelProfile {
    public static final String ADAPTER = "model.readonly.v1";
    public static final String MODULE = "free.readonly";
    public static final String SLOT = "WORKFLOW_READ_ONLY";
    public static final String WORK = "get_workflow_node_work";
    public static final String INPUT = "read_workflow_node_input";
    public static final String SUBMIT = "submit_workflow_node_result";
    public static final String FILES = "list_workflow_input_files";
    public static final String FILE = "read_workflow_input_file";
    public static final List<String> TOOLS = List.of(WORK, INPUT, FILES, FILE, SUBMIT);
    public static final String WRITE_ADAPTER = "model.write.v1";
    public static final String WRITE_MODULE = "free.write";
    public static final String WRITE_SLOT = "WORKFLOW_WRITE";
    public static final String WRITE_AGENT = "loopper-workflow-write";
    public static boolean contains(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY || profile == OpenCodeClient.SessionProfile.WORKFLOW_WRITE;
    }
    public static boolean writeModule(String module){return WRITE_MODULE.equals(module)||io.opencode.loopper.workflow.WorkflowTestWrite.supports(module);}
    public static boolean writer(String adapter) { return WRITE_ADAPTER.equals(adapter); }
    public static String slot(String adapter) { return writer(adapter) ? WRITE_SLOT : SLOT; }
    public static OpenCodeClient.SessionProfile profile(String adapter) {
        return writer(adapter) ? OpenCodeClient.SessionProfile.WORKFLOW_WRITE : OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY;
    }
    private WorkflowModelProfile() { }
}
