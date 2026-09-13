package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskProgressRow;
import io.opencode.loopper.template.TemplateTaskDefinition;
import java.util.*;

/** A conservative display projection. Batch completion never completes the Task. */
final class TemplateProgressFlow {
    record Flow(List<TemplateTaskProgress.Step> steps,String currentPhase) { }
    static Flow project(TemplateTaskProgressRow r,String state) {
        boolean known=r.reviewBatches()!=null && r.contributorBatches()!=null;
        boolean review=TemplateTaskDefinition.requiresDualReview(r.templateVersion());
        var keys=new ArrayList<>(List.of("COLLECT","CODE"));
        if(r.contributorBatches()!=null && r.contributorBatches()>0)keys.add("CONTRIBUTORS");
        keys.add("REPORT");if(review)keys.add("REVIEW");keys.add("COMPLETE");
        String current=!known?"COLLECT":r.completedReviews()<r.reviewBatches()?"CODE":
                r.completedContributors()<r.contributorBatches()?"CONTRIBUTORS":"REPORT";
        if(review && state.equals("JUDGING"))current="REVIEW";
        if(state.equals("COMPLETED"))current="COMPLETE";
        boolean pending=Set.of("PENDING_START","QUEUED","PREPARING","READY").contains(state);
        boolean interrupted=Set.of("FAILED","CANCELLED","STOPPING","WAITING_INPUT","PAUSED","RETRY_WAIT","AWAITING_DECISION","SUPERSEDED","SUCCEEDED").contains(state);
        int active=keys.indexOf(current);var steps=new ArrayList<TemplateTaskProgress.Step>();
        for(int i=0;i<keys.size();i++) {
            String key=keys.get(i);String tone;
            if(!known && state.equals("COMPLETED"))tone=key.equals("COMPLETE")?"COMPLETE":"UNKNOWN";
            else if(pending)tone="PENDING";
            else if(i<active)tone="COMPLETE";
            else if(i==active)tone=state.equals("COMPLETED")?"COMPLETE":interrupted?"INTERRUPTED":"ACTIVE";
            else tone="PENDING";
            steps.add(new TemplateTaskProgress.Step(key,label(key),tone));
        }
        return new Flow(List.copyOf(steps),pending||interrupted?state:current);
    }
    private static String label(String key) {
        return switch(key) {case "COLLECT"->"采集提交";case "CODE"->"代码分析";case "CONTRIBUTORS"->"人员贡献";
            case "REPORT"->"生成并校验报告";case "REVIEW"->"报告评审";default->"完成";};
    }
}
