package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowTestProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WorkflowTestProfileExecution {
    private final WorkflowTestProfileStore store;
    private final WorkflowTestProfileBuilder builder;
    public WorkflowTestProfileExecution(WorkflowTestProfileStore store,WorkflowTestProfileBuilder builder){this.store=store;this.builder=builder;}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void advance(String id) {
        var context=store.context(id);if(context==null)return;
        WorkflowTestProfile.Frozen result=null;String code=null,message=null;
        try{result=builder.build(context);}
        catch(BadRequestException|ConflictException invalid){code=WorkflowFailures.code(invalid);message=invalid.getMessage();
            if(code.equals("SOURCE_TEST_CONFIGURATION_REQUIRED"))message+="。请确认项目测试配置；需要读取新配置时，在计划中新增源码采集节点并重新绑定输入。";
        }
        catch(tools.jackson.core.JacksonException invalid){code="WORKFLOW_TEST_PROFILE_INVALID";message="冻结的测试配置格式无法解析，请核对来源文件后重新创建源码资料。";}
        store.finish(context,builder.delivery(result,code,message),result!=null);
    }
}
