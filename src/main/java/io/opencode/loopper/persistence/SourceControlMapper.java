package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceControlMapper {
    @Insert("INSERT OR IGNORE INTO source_template_control(run_id) VALUES(#{id})")
    void initialize(String id);
    @Update("UPDATE source_template_control SET consecutive_errors=consecutive_errors+1 WHERE run_id=#{id}")
    void error(String id);
    @Update("UPDATE source_template_control SET consecutive_errors=0 WHERE run_id=#{id}")
    void healthy(String id);
    @Select("SELECT consecutive_errors FROM source_template_control WHERE run_id=#{id}")
    int errors(String id);
    @Select("SELECT COALESCE((SELECT recoveries FROM source_template_control WHERE run_id=#{id}),0)")
    int recoveries(String id);
    @Update("UPDATE source_template_control SET recoveries=recoveries+1,consecutive_errors=0 WHERE run_id=#{id} AND recoveries<#{maximum}")
    int recover(String id, int maximum);
}
