package io.opencode.loopper.service.ppt.generation;

import io.opencode.loopper.persistence.PptGenerationMapper;
import io.opencode.loopper.service.ppt.*;
import org.springframework.stereotype.Service;

/** A retry of an automatic output resumes its exact owner; historical manual jobs remain independent. */
@Service
public class PptOutputRecovery {
    private final PptGenerationMapper mapper;
    private final PptGenerationPersistence persistence;
    private final PptGenerationCoordinator coordinator;
    private final PptJobs jobs;
    private final PptDocuments documents;
    public PptOutputRecovery(PptGenerationMapper mapper,PptGenerationPersistence persistence,PptGenerationCoordinator coordinator,
            PptJobs jobs,PptDocuments documents) {
        this.mapper=mapper;this.persistence=persistence;this.coordinator=coordinator;this.jobs=jobs;this.documents=documents;
    }
    public PptJobs.View retry(String document,String job) {
        var output=jobs.get(document,job);
        var owner=mapper.byJob(job);if(owner.isEmpty())return jobs.retry(document,job);
        var row=owner.get();String generationId=row.id();
        if(output.state().equals("COMPLETED")&&(!job.equals(row.jobId())||!row.state().equals("FAILED")))return output;
        if(!row.documentId().equals(document)||!job.equals(row.jobId())
                ||mapper.latest(document).filter(latest->latest.id().equals(generationId)).isEmpty())
            throw PptSupport.conflict("该作业不再属于当前制作步骤，请从当前制作进度继续");

        coordinator.tick(row.id());row=mapper.get(row.id()).orElseThrow();
        if(row.state().equals("FAILED")) {
            if(documents.get(document).revision()!=output.revision())throw PptSupport.conflict("作品已有新版本，请从当前制作进度继续");
            String key="output_retry_"+row.id()+"_"+row.version();
            String sha=PptSupport.hash("PPT_OUTPUT_RETRY_V1:"+document+":"+job+":"+row.id()+":"+row.version()+":"+output.revision());
            var resumed=persistence.resume(row,key,sha,output.revision());
            coordinator.tick(resumed.id());
        }else if(row.state().equals("STOPPED")||row.state().equals("STOPPING"))
            throw PptSupport.conflict("制作已停止，请从制作进度明确继续");
        return jobs.get(document,job);
    }
}
