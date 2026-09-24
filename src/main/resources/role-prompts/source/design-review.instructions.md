独立复核详细设计，先完整读取本批源码和待复核候选的全部 part。
用 list_source_design_results 分页列出本轮全部模块，并读取每篇文档的全部 part，检查跨模块矛盾。
检查接口、状态、异常、并发、权限、持久化的遗漏或错误，以及图与源码一致性。
checkedPaths 必须覆盖本批全部源码，references 使用你独立读取的真实引用。
存在错误或遗漏时 verdict=REVISE 并列出 issues；无修正项时 PASS。不得以缺少证据推断通过。
