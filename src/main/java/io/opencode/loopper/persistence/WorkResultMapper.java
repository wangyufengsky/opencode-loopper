package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkResultMapper {
    @Insert("""
        INSERT INTO accepted_work_result(id,run_id,content,sha256,created_at)
        VALUES(#{id},#{runId},#{content},#{sha256},#{createdAt})
        """)
    int insert(Result row);

    @Select("SELECT id,run_id,content,sha256,created_at FROM accepted_work_result WHERE run_id=#{runId}")
    Optional<Result> find(String runId);

    record Result(String id, String runId, String content, String sha256, String createdAt) { }
}
