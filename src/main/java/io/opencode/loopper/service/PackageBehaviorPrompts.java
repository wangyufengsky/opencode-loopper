package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Fixed, bounded examples shared by the production preparation, reviewer and offline evaluator. */
final class PackageBehaviorPrompts {
    static final String VERSION = "PACKAGE_BEHAVIOR_PROMPT_20260907_R3";
    private PackageBehaviorPrompts() { }
    static PackageBehaviorContract example() {
        return new PackageBehaviorContract(PackageBehaviorContract.VERSION,
                List.of(new Variable("before", "ENUM", "INPUT", List.of("CLAIMED", "STOPPING"), List.of("REQ-L001")),
                        new Variable("after", "ENUM", "OUTPUT", List.of("STOPPING"), List.of("REQ-L001"))),
                List.of(new Obligation("CANCEL", List.of("REQ-L001"), "cancel", Expr.eq("before", "CLAIMED"), Map.of("after", "STOPPING")),
                        new Obligation("REPEAT", List.of("REQ-L001"), "cancel", Expr.eq("before", "STOPPING"), Map.of("after", "STOPPING"))),
                List.of(), List.of());
    }
    static String extract(String original, String scope) {
        return VERSION + "；触发原因=" + PackageBehaviorPreparation.reasons(original) + "\n只读语义整理，本修订仅一次。只返回 PACKAGE_BEHAVIOR_V1 JSON，不调用候选提交/提问工具。\n"
                + grammar() + "\n冻结工作包范围与已有决策：\n" + scope + "\n原文（不得被模型改写）：\n" + PackageRequirementSources.prompt(original);
    }
    static String review(String original, String extraction, String scope, ObjectMapper json) {
        return VERSION + "\n你是独立来源复核者；这是新的只读会话。只核对原文和整理材料，不调用提交/提问工具。"
                + "先从原文逐项找出行为，再检查整理是否保留所有适用分支、否定、例外、首次转换、并发赢家、幂等、补偿和不变量。"
                + "不要因为有引用、结构正确或整理者声称完成而认可它。可在本轮修正整理，最终 book 是你的完整复核版本；不要改原文。"
                + "变量必须有清晰领域含义，前态 INPUT 和后态 OUTPUT 分开。多种可观察行为不同的未定业务选择或无法忠实表达的业务内容写入 findings，不能猜默认值。"
                + "尚无测试可规划待建测试，未知仓库事实不是能力不存在。NON_BEHAVIOR 仅用于路径、执行范围、测试策略等由其他合同负责的内容，不能用来省略业务分支。\n"
                + grammar() + "\n只返回一个 JSON 对象，所有字段必须存在：\n"
                + "{\"version\":\"PACKAGE_SOURCE_REVIEW_V1\",\"sourceSha256\":\"" + PackageBehaviorSourceReview.hash(original)
                + "\",\"extractionSha256\":\"" + PackageBehaviorSourceReview.hash(extraction)
                + "\",\"contextSha256\":\"" + PackageBehaviorSourceReview.hash(scope) + "\",\"book\":<完整义务对象>,\"sourceChecks\":[{\"sourceRef\":\"REQ-L001\",\"quote\":\"原文中不超过1000字符的精确片段\","
                + "\"disposition\":\"MODELED\",\"obligationRefs\":[\"义务或不变量键\"],\"reason\":\"逐分支核对的依据与变量含义\"}],\"findings\":[]}\n"
                + "每个原文来源（包括 NON_BEHAVIOR 的范围、测试说明、标题和说明行）都恰好一条 sourceChecks，不可只写业务行。MODELED 必须对应 book 中相同来源的义务；NON_BEHAVIOR 引用为空且列入 unmodeledSources。"
                + "findings 是字符串数组（不是对象数组），最多32条、每条明确指出来源和未确认事项；只在所有业务语义已对齐时使用空数组。不得虚构人工确认。\n"
                + "独立的完整复核形状示例（示例原文第一行是取消行为，第二行是测试范围；哈希和内容均须换成本次值）：\n" + json.writeValueAsString(reviewExample(json)) + "\n"
                + "冻结工作包范围与已有决策：\n" + scope + "\n权威原文：\n" + PackageRequirementSources.prompt(original)
                + "\n待复核整理（非权威）：\n" + extraction;
    }
    static String candidate(String book, ObjectMapper json) {
        var example = new Branch("SC-1", List.of("CANCEL"), "cancel", Expr.eq("before", "CLAIMED"), Map.of("after", "STOPPING"));
        return "\n" + VERSION + "：此运行冻结了有界行为义务。READY 额外提交 behaviorBranches，每个 scenarios[].key 恰好一条。"
                + "沿用冻结变量/事件/义务键，只设计 when 和 effects，不提交或修改 book。所有适用输入均须覆盖，不能用引用代替逻辑。"
                + "effects 必须赋值全部 OUTPUT，不得用 OUTPUT 作前置条件。scenarios 的 precondition/action/observableResult/invariant 由服务端按结构化分支生成；标题仅作导航。"
                + "收到 SEMANTIC_* 错误时按具体反例修复，保留键和其他分支，重提完整对象；不得添加默认行为、删除义务或把结构化错误写成业务缺口。\n"
                + "behaviorBranches 单条形状示例（替换为本运行键和值）：" + json.writeValueAsString(example)
                + "\n以下是不可修改的已复核有限域模型（来源对齐是模型证据，不是形式证明）：\n" + book;
    }
    static PackageBehaviorSourceReview.Review reviewExample(ObjectMapper json) {
        String source = "领取后取消进入正在停止；已在正在停止状态时重复取消保持正在停止。\n测试范围为取消行为的聚焦测试。";
        var base = example();
        var book = new PackageBehaviorContract(base.version(), base.variables(), base.obligations(), base.invariants(), List.of("REQ-L002"));
        return new PackageBehaviorSourceReview.Review(PackageBehaviorSourceReview.VERSION,
                PackageBehaviorSourceReview.hash(source), PackageBehaviorSourceReview.hash("draft"), PackageBehaviorSourceReview.hash("{}"), book,
                List.of(new PackageBehaviorSourceReview.SourceCheck("REQ-L001", "取消", "MODELED", List.of("CANCEL", "REPEAT"), "before 为取消前的状态，after 为取消后的状态，首次和重复操作分别覆盖"),
                        new PackageBehaviorSourceReview.SourceCheck("REQ-L002", "测试范围", "NON_BEHAVIOR", List.of(), "测试范围由既有验证合同负责，不是待省略的业务分支")), List.of());
    }
    private static String grammar() {
        return "模型：version=PACKAGE_BEHAVIOR_V1；variables/obligations/invariants/unmodeledSources 四个数组。每个原文来源至少被义务、不变量或 unmodeledSources 保留。"
                + "unmodeledSources 只能是来源键的字符串数组，例如 [\"REQ-L002\"]，禁止放入 text/reason/sourceRefs 对象。非业务说明的理由放到 sourceChecks.reason。变量 type=BOOLEAN/ENUM/INTEGER，role=INPUT/OUTPUT，values 为显式离散字符串值；BOOLEAN 的 values 必须恰好为 [\"false\",\"true\"]，比较和值也只用小写字符串 false/true，不使用 TRUE/FALSE、0/1 或 JSON 布尔值。ENUM 值自定但全书精确一致；INTEGER 只涵盖列出值，不代表未声明的整数区间。"
                + "禁止用截断的几个值冒充无限数域；可用有原文依据的布尔谓词表示条件类别，并在复核依据说明含义。"
                + "when/predicate 表达式均包含 op,variable,value,args 四字段；eq/ne 是比较（args=[]），all/any/not 的 variable/value=null；true/false 的 args=[]。"
                + "all/any 不为空，not 恰好一个子式；例外显式编码为条件及否定，所有或分支都覆盖。每条 effects 为所有 OUTPUT 键到有限域值的对象。"
                + "32变量/每域32值、64义务、32不变量、表达式深6/128节点；book 最多32KiB，超出或未知业务写 findings，不截断逻辑。"
                + "后态和前态必须分开命名；例如首次取消必须覆盖 CLAIMED→STOPPING，重复取消另外覆盖 STOPPING→STOPPING。"
                + "管理员或有效委托允许且归档全拒绝，应编码 allow=!archived&&(admin||validDelegate)，拒绝必须保留管理员例外；不能按第一条命中掩盖冲突。"
                + "完整语法例子，仅示范取消行为，不能复制成当前需求：\n" + new ObjectMapper().writeValueAsString(example());
    }
}
