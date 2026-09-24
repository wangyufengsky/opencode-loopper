package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Immutable role publication and owner/session binding rows. */
@Mapper
public interface RoleConfigurationMapper {
    record Definition(String roleId, String displayName, String description, String groupKey,
                      String groupLabel, String origin, String createdAt) { }
    record Bootstrap(String name, String sourceSha256, String bindingsSha256,
                     int bindingCount, String completedAt) { }
    record Revision(String revisionId, String roleId, int revisionNumber, String manifestJson,
                    String promptFragmentsJson, String contentSha256, String sourceSha256, String sourceKind,
                    String publishedAt) { }
    record Binding(String slot, String adapterProfile, String displayName, String purpose,
                   String revisionId, long version, String updatedAt) { }
    record BindingView(String slot, String profile, String activeRoleId, String activeRevisionId,
                       long bindingVersion, String label, String purpose) { }
    record Summary(String roleId, String displayName, String description, String groupKey,
                   String groupLabel, String origin, String latestRevisionId,
                   int latestRevisionNumber, String activeSlotsJson) { }
    record OwnerBinding(String ownerType, String ownerId, String slot, String revisionId,
                        String parentType, String parentId, String frozenAt) { }
    record OwnerSnapshot(String ownerType, String ownerId, String parentType, String parentId,
                         int bindingCount, String bindingsSha256, String frozenAt) { }
    record SessionSnapshot(String sessionKey, String ownerType, String ownerId, String slot,
                           String revisionId, String revisionSha256, String adapterProfile,
                           String adapterVersion,
                           String permissionPolicyJson, String permissionPolicySha256,
                           String safePromptSha256, String frozenAt) { }
    record PromptDispatch(String sessionKey, String messageKey, String businessSha256,
                          String effectiveSha256, String createdAt) { }
    record Receipt(String idempotencyKey, String sourceSha256, String requestSha256,
                   String resultJson, String createdAt) { }

    @Select("SELECT * FROM role_configuration_bootstrap WHERE name='builtin'")
    Bootstrap bootstrap();
    @Insert("INSERT INTO role_configuration_bootstrap(name,source_sha256,bindings_sha256,binding_count,completed_at) VALUES(#{name},#{sourceSha256},#{bindingsSha256},#{bindingCount},#{completedAt})")
    int insertBootstrap(Bootstrap row);
    @Update("UPDATE role_configuration_bootstrap SET source_sha256=#{sourceSha256},bindings_sha256=#{bindingsSha256},binding_count=#{bindingCount},completed_at=#{completedAt} WHERE name='builtin' AND source_sha256=#{previousSha}")
    int updateBootstrap(String sourceSha256, String bindingsSha256, int bindingCount,
                        String completedAt, String previousSha);

    @Select("SELECT * FROM role_definition WHERE role_id=#{roleId}")
    Definition definition(String roleId);
    @Select("SELECT * FROM role_definition WHERE role_id>#{after} AND (#{query}='' OR instr(lower(role_id),lower(#{query}))>0 OR instr(lower(display_name),lower(#{query}))>0) ORDER BY role_id LIMIT #{limit}")
    List<Definition> definitions(String after, String query, int limit);
    @Select("""
        SELECT d.*, r.revision_id AS latest_revision_id, r.revision_number AS latest_revision_number,
        COALESCE((SELECT json_group_array(b.slot) FROM role_binding b JOIN role_revision x ON x.revision_id=b.revision_id WHERE x.role_id=d.role_id),'[]') AS active_slots_json
        FROM role_definition d LEFT JOIN role_revision r ON r.role_id=d.role_id
          AND r.revision_number=(SELECT max(z.revision_number) FROM role_revision z WHERE z.role_id=d.role_id)
        WHERE d.role_id>#{after} AND (#{query}='' OR instr(lower(d.role_id),lower(#{query}))>0 OR instr(lower(d.display_name),lower(#{query}))>0)
        ORDER BY d.role_id LIMIT #{limit}
        """)
    List<Summary> summaries(String after, String query, int limit);
    @Select("""
        SELECT d.*, r.revision_id AS latest_revision_id, r.revision_number AS latest_revision_number,
        COALESCE((SELECT json_group_array(b.slot) FROM role_binding b JOIN role_revision x ON x.revision_id=b.revision_id WHERE x.role_id=d.role_id),'[]') AS active_slots_json
        FROM role_definition d LEFT JOIN role_revision r ON r.role_id=d.role_id
          AND r.revision_number=(SELECT max(z.revision_number) FROM role_revision z WHERE z.role_id=d.role_id)
        WHERE d.role_id=#{roleId}
        """)
    Summary summary(String roleId);
    @Insert("INSERT INTO role_definition(role_id,display_name,description,group_key,group_label,origin,created_at) VALUES(#{roleId},#{displayName},#{description},#{groupKey},#{groupLabel},#{origin},#{createdAt})")
    int insertDefinition(Definition row);
    @Update("UPDATE role_definition SET display_name=#{displayName},description=#{description},group_key=#{groupKey},group_label=#{groupLabel},origin=#{origin} WHERE role_id=#{roleId}")
    int updateDefinition(Definition row);
    @Select("SELECT * FROM role_revision WHERE revision_id=#{id}")
    Revision revision(String id);
    @Select("SELECT * FROM role_revision WHERE role_id=#{roleId} ORDER BY revision_number DESC LIMIT 1")
    Revision latest(String roleId);
    @Select("SELECT * FROM role_revision WHERE role_id=#{roleId} AND revision_number<#{before} ORDER BY revision_number DESC LIMIT #{limit}")
    List<Revision> revisions(String roleId, int before, int limit);
    @Select("SELECT * FROM role_revision WHERE role_id=#{roleId} AND content_sha256=#{sha}")
    Revision byContent(String roleId, String sha);
    @Insert("INSERT INTO role_revision(revision_id,role_id,revision_number,manifest_json,prompt_fragments_json,content_sha256,source_sha256,source_kind,published_at) VALUES(#{revisionId},#{roleId},#{revisionNumber},#{manifestJson},#{promptFragmentsJson},#{contentSha256},#{sourceSha256},#{sourceKind},#{publishedAt})")
    int insertRevision(Revision row);

    @Select("SELECT * FROM role_binding ORDER BY slot")
    List<Binding> bindings();
    @Select("SELECT b.slot,b.adapter_profile AS profile,r.role_id AS active_role_id,b.revision_id AS active_revision_id,b.version AS binding_version,b.display_name AS label,b.purpose FROM role_binding b LEFT JOIN role_revision r ON r.revision_id=b.revision_id ORDER BY b.slot")
    List<BindingView> bindingViews();
    @Select("SELECT * FROM role_binding WHERE slot=#{slot}")
    Binding binding(String slot);
    @Insert("INSERT OR IGNORE INTO role_binding(slot,adapter_profile,display_name,purpose,revision_id,version,updated_at) VALUES(#{slot},#{adapterProfile},#{displayName},#{purpose},#{revisionId},#{version},#{updatedAt})")
    int insertBinding(Binding row);
    @Update("UPDATE role_binding SET revision_id=#{revisionId},version=version+1,updated_at=#{time} WHERE slot=#{slot} AND version=#{expectedVersion}")
    int activate(String slot, String revisionId, long expectedVersion, String time);

    @Select("SELECT * FROM role_owner_binding WHERE owner_type=#{type} AND owner_id=#{id} ORDER BY slot")
    List<OwnerBinding> ownerBindings(String type, String id);
    @Select("SELECT * FROM role_owner_snapshot WHERE owner_type=#{type} AND owner_id=#{id}")
    OwnerSnapshot ownerSnapshot(String type, String id);
    @Insert("INSERT INTO role_owner_binding(owner_type,owner_id,slot,revision_id,parent_type,parent_id,frozen_at) VALUES(#{ownerType},#{ownerId},#{slot},#{revisionId},#{parentType},#{parentId},#{frozenAt})")
    int insertOwnerBinding(OwnerBinding row);
    @Insert("INSERT INTO role_owner_snapshot(owner_type,owner_id,parent_type,parent_id,binding_count,bindings_sha256,frozen_at) VALUES(#{ownerType},#{ownerId},#{parentType},#{parentId},#{bindingCount},#{bindingsSha256},#{frozenAt})")
    int insertOwnerSnapshot(OwnerSnapshot row);

    @Select("SELECT * FROM role_session_snapshot WHERE session_key=#{key}")
    SessionSnapshot sessionSnapshot(String key);
    @Insert("INSERT INTO role_session_snapshot(session_key,owner_type,owner_id,slot,revision_id,revision_sha256,adapter_profile,adapter_version,permission_policy_json,permission_policy_sha256,safe_prompt_sha256,frozen_at) VALUES(#{sessionKey},#{ownerType},#{ownerId},#{slot},#{revisionId},#{revisionSha256},#{adapterProfile},#{adapterVersion},#{permissionPolicyJson},#{permissionPolicySha256},#{safePromptSha256},#{frozenAt})")
    int insertSessionSnapshot(SessionSnapshot row);
    @Select("SELECT * FROM role_prompt_dispatch WHERE session_key=#{sessionKey} AND message_key=#{messageKey}")
    PromptDispatch promptDispatch(String sessionKey, String messageKey);
    @Insert("INSERT INTO role_prompt_dispatch(session_key,message_key,business_sha256,effective_sha256,created_at) VALUES(#{sessionKey},#{messageKey},#{businessSha256},#{effectiveSha256},#{createdAt})")
    int insertPromptDispatch(PromptDispatch row);

    @Select("SELECT * FROM role_import_receipt WHERE idempotency_key=#{key}")
    Receipt receipt(String key);
    @Insert("INSERT INTO role_import_receipt(idempotency_key,source_sha256,request_sha256,result_json,created_at) VALUES(#{idempotencyKey},#{sourceSha256},#{requestSha256},#{resultJson},#{createdAt})")
    int insertReceipt(Receipt row);
    @Insert("INSERT INTO role_configuration_audit(id,event_type,role_id,revision_id,slot,detail_json,created_at) VALUES(#{id},#{eventType},#{roleId},#{revisionId},#{slot},#{detailJson},#{createdAt})")
    int audit(String id, String eventType, String roleId, String revisionId, String slot,
              String detailJson, String createdAt);
}
