package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceTemplateMapper {
    @Select("SELECT * FROM source_template_run WHERE id=#{id}")
    Optional<SourceTemplateRunRow> find(String id);
    @Select("SELECT * FROM source_template_run WHERE request_key=#{key}")
    Optional<SourceTemplateRunRow> request(String key);
    @Select("SELECT * FROM source_template_run WHERE designer_id=#{id}")
    Optional<SourceTemplateRunRow> designer(String id);
    @Select("SELECT * FROM source_template_run WHERE task_id=#{id}")
    Optional<SourceTemplateRunRow> task(String id);
    @Insert("""
        INSERT INTO source_template_run(id,request_key,request_sha256,project_id,template_id,template_version,title,state,
          parameters_json,contract_json,created_at,updated_at,version)
        VALUES(#{id},#{requestKey},#{requestSha256},#{projectId},#{templateId},#{templateVersion},#{title},#{state},
          #{parametersJson},#{contractJson},#{createdAt},#{updatedAt},#{version})
        """)
    int insert(SourceTemplateRunRow row);
    @Update("""
        UPDATE source_template_run SET state=#{state},resume_state=#{resume},waiting_reason_code=#{code},
          waiting_message=#{message},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version}
        """)
    int transition(String id, long version, String state, String resume, String code, String message, String now);
    @Update("""
        UPDATE source_template_run SET snapshot_json=#{snapshot},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND state='PREPARING' AND snapshot_json IS NULL
        """)
    int snapshot(String id, long version, String snapshot, String now);
    @Update("""
        UPDATE source_template_run SET snapshot_json=#{snapshot},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND state='PREPARING' AND json_extract(snapshot_json,'$.ready')=0
        """)
    int snapshotReady(String id, long version, String snapshot, String now);
    @Insert("""
        INSERT INTO source_template_file(run_id,ordinal,path,target,size_bytes,sha256,exclusion)
        VALUES(#{runId},#{ordinal},#{path},#{target},#{sizeBytes},#{sha256},#{exclusion})
        """)
    int insertFile(File row);
    @Select("SELECT * FROM source_template_file WHERE run_id=#{id} ORDER BY ordinal")
    List<File> files(String id);
    @Select("SELECT * FROM source_template_file WHERE run_id=#{id} AND path=#{path}")
    Optional<File> file(String id, String path);
    @Select("""
        SELECT f.*,COALESCE(c.status,CASE WHEN f.exclusion IS NOT NULL THEN 'EXCLUDED' ELSE 'PENDING' END) AS status,
          NULL AS result_json
        FROM source_template_file f LEFT JOIN source_template_coverage c ON c.run_id=f.run_id AND c.path=f.path
        WHERE f.run_id=#{id} AND f.target=1 AND f.ordinal>#{after} ORDER BY f.ordinal LIMIT #{limit}
        """)
    List<Coverage> coverage(String id, int after, int limit);
    @Select("""
        SELECT f.*,COALESCE(c.status,CASE WHEN f.exclusion IS NOT NULL THEN 'EXCLUDED' ELSE 'PENDING' END) AS status,
          COALESCE(c.result_json,'{}') AS result_json FROM source_template_file f
        LEFT JOIN source_template_coverage c ON c.run_id=f.run_id AND c.path=f.path
        WHERE f.run_id=#{id} AND f.target=1 AND f.path=#{path}
        """)
    Optional<Coverage> coverageItem(String id, String path);
    @Select("""
        SELECT COALESCE(c.status,CASE WHEN f.exclusion IS NOT NULL THEN 'EXCLUDED' ELSE 'PENDING' END) AS status,count(*) AS count
        FROM source_template_file f LEFT JOIN source_template_coverage c ON c.run_id=f.run_id AND c.path=f.path
        WHERE f.run_id=#{id} AND f.target=1 GROUP BY 1 ORDER BY 1
        """)
    List<Count> coverageCounts(String id);
    @Select("""
        SELECT id FROM source_template_run WHERE id>#{after}
          AND state NOT IN ('PENDING_START','COMPLETED','CANCELLED','WAITING_INPUT') ORDER BY id LIMIT 100
        """)
    List<String> activeAfter(String after);
    @Update("""
        UPDATE source_template_run SET stop_target=#{target},resume_state=#{resume},waiting_reason_code=#{code},
          waiting_message=#{message},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version}
        """)
    int stopIntent(String id, long version, String target, String resume, String code, String message, String now);
    @Select("SELECT digest FROM source_template_command WHERE run_id=#{id} AND request_key=#{key}")
    Optional<String> command(String id, String key);
    @Insert("INSERT INTO source_template_command VALUES(#{id},#{key},#{digest},#{now})")
    int commandRecord(String id, String key, String digest, String now);
    @Insert("""
        INSERT INTO source_template_coverage(run_id,path,status,result_json,updated_at) VALUES(#{run},#{path},#{status},#{result},#{now})
        ON CONFLICT(run_id,path) DO UPDATE SET status=excluded.status,result_json=excluded.result_json,updated_at=excluded.updated_at
        """)
    int coverageResult(String run, String path, String status, String result, String now);
    @Update("UPDATE source_template_run SET archived=#{archived},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int archive(String id, long version, int archived, String now);
    record File(String runId, int ordinal, String path, int target, long sizeBytes, String sha256, String exclusion) { }
    record Coverage(String runId, int ordinal, String path, int target, long sizeBytes, String sha256,
                    String exclusion, String status, String resultJson) { }
    record Count(String status, long count) { }
}
