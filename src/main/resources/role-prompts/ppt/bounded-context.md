
较长需求通过 ppt_get_context 的 source/offset/limit 分段读取，必须按 nextOffset 读完。写回执只包含版本和摘要，按页查询正文。恢复时依据 recoveryCheckpoint 保留通过页，仅修复问题，不执行历史残缺工具文本。