package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentPlanTransportMapper {
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    @Select("SELECT * FROM document_plan_transport WHERE plan_id=#{id}")
    Optional<Transport> find(String id);
    @Insert("INSERT INTO document_plan_transport(plan_id,creation_plan_json,created_at) VALUES(#{planId},#{creationPlanJson},#{createdAt}) ON CONFLICT(plan_id) DO NOTHING")
    int insert(Transport row);
    @Update("UPDATE document_plan_transport SET prompt_json=#{prompt},prompt_sha256=#{hash} WHERE plan_id=#{id} AND prompt_json IS NULL")
    int prompt(@Param("id") String id,@Param("prompt") String prompt,@Param("hash") String hash);
    record Transport(String planId,String creationPlanJson,String promptJson,String promptSha256,String createdAt) { }
}
