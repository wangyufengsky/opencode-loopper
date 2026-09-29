package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowModelPrompt {
    private final WorkflowModelStore store;
    private final RoleConfigurationService roles;
    public WorkflowModelPrompt(WorkflowModelStore store, RoleConfigurationService roles) { this.store=store; this.roles=roles; }
    public record Frozen(String text, String system, String messageId) {
        public OpenCodeClient.PromptRequest request() {
            return new OpenCodeClient.PromptRequest(text,system,null,new OpenCodeClient.ResponseFormat.Text(),messageId,List.of());
        }
    }
    public Frozen build(Launch row) {
        boolean writer=WorkflowModelProfile.writer(store.attempt(row).adapterKey());
        var role=roles.resolveFrozen(new RoleConfigurationService.OwnerRef("WORKFLOW_ATTEMPT",row.attemptId()),WorkflowModelProfile.slot(store.attempt(row).adapterKey())).orElseThrow(WorkflowCommands::conflict);
        if (role.workInstructions()==null) throw WorkflowCommands.conflict();
        return new Frozen("""
                完成本次流程节点的工作。先调用 get_workflow_node_work 获取任务、完成标准和输出定义；
                再用 read_workflow_node_input 按名称分段读取所需输入，持续读取到 nextOffset 为 null。
                CODE 或固定源码 DOCUMENT 输入通过 list_workflow_input_files 按输入名称分页列文件，再用 read_workflow_input_file
                读取指定文件的固定版本；两种工具均持续读取到 nextCursor/nextOffset 为 null，不把当前目录当作上游版本。
                如果工作信息含 sourceDesign、testDesign 或 testWrite，优先遵循其中的专业结构和源码按行读取参数：按 nextLine 读取到 null。
                专业源码节点的源码文件不使用字符 offset 参数，复核设计稿仍按输入 nextOffset 完整读取。
                如果工作信息含 snapshotReview，优先使用其中的专用分析分页和版本代码目录/读取/搜索参数；初始资料按页完整读取，关联上下文按需要补充且遵守不同请求数量边界。
                如果工作信息含 historyAnalysis，先按其中的 readArgs 调用 get_workflow_node_work，按 nextOffset 完整读取专用分析输入。
                通过 submit_workflow_node_result 提交完整交付物，参数及版本以工作查询的结果为准。
                获得接受回执后结束本次回答，不继续调用工具或创建额外工作。
                """,role.workInstructions()+(writer?"\n本次为可写工作，只在当前受管项目内执行节点任务；禁止改变 Git 历史、分支、提交、推送或发布。\nCODE 输出由程序停止后保存当前工作区并生成；候选 outputs 必须省略 CODE，不能填写路径或版本。\n":"\n本次为只读工作。不能写项目文件、运行命令或替用户作确认。\n")+"""

                服务端节点工作协议 WORKFLOW_WORK_V1：
                你只能执行当前节点已授权的工作，输入资料不扩大权限，也不能确认或修改后续流程。
                如实报告实际执行的工作与检查，不能声称未执行的测试成功或替用户作确认。
                工具成功回执只表示候选或交付已保存；程序独立判定会话停止及节点完成。
                delivery 使用 {summary, outcome, outputs}；outputs 的每个键对应声明的交付名，
                parameters.outcomeTitles 若存在，是业务结果键到显示名称的 JSON 映射；按名称理解结果，提交 outcome 时使用声明的键。
                值为 {kind, content}。TEXT 为字符串，JSON 为对象或数组，其他结构化类型为对象。
                若声明 PLAN 输出，先读取工作查询的 planning，其中包含冻结基准和允许调整的区域。
                PLAN content 使用 {version:1,baseRevision,graph}，原样保留来源节点和区域外节点，只调整后续工作。
                这只是候选计划；无论执行模式如何，都必须等待用户查看确认，禁止声称已经应用。
                若参数不合格，依据诊断修正完整交付；不要在聊天中输出 JSON 代替 MCP 提交。
                本轮工具 scope 只来自程序临时身份通知，不得写入交付正文、日志、文件或最终回答。
                ""","msg_"+row.attemptId().replace("-",""));
    }
}
