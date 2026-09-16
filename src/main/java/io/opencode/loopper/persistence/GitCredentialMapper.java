package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface GitCredentialMapper {
    record Row(String scopeKey, String projectId, String mode, String serverUrl, String username,
               String kind, String secretRef, long version, String updatedAt) { }
    @Select("SELECT * FROM git_credential WHERE scope_key=#{scope}")
    Optional<Row> find(String scope);
    @Insert("""
            INSERT INTO git_credential(scope_key,project_id,mode,server_url,username,kind,secret_ref,version,updated_at)
            VALUES(#{row.scopeKey},#{row.projectId},#{row.mode},#{row.serverUrl},#{row.username},#{row.kind},#{row.secretRef},#{row.version},#{row.updatedAt})
            ON CONFLICT(scope_key) DO UPDATE SET mode=excluded.mode,server_url=excluded.server_url,
                username=excluded.username,kind=excluded.kind,secret_ref=excluded.secret_ref,
                version=excluded.version,updated_at=excluded.updated_at
            WHERE git_credential.version=#{expected}
            """)
    int save(@Param("row") Row row, @Param("expected") long expected);
    @Insert("INSERT INTO git_credential_audit(id,scope_key,action,version,occurred_at) VALUES(#{id},#{scope},#{action},#{version},#{now})")
    void audit(String id, String scope, String action, long version, String now);
}
