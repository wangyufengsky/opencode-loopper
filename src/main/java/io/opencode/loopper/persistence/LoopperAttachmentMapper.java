package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LoopperAttachmentMapper {
    @Insert("""
            INSERT INTO designer_attachment_submission(
              id,designer_session_id,scope_key,work_package_id,request_sha256,state,
              old_external_session_id,new_external_session_id,external_message_id,error_code,error_detail,
              created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{scopeKey},#{workPackageId},#{requestSha256},#{state},
              #{oldExternalSessionId},#{newExternalSessionId},#{externalMessageId},#{errorCode},#{errorDetail},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertDesignerAttachmentSubmission(DesignerAttachmentSubmissionRow row);

    @Select("SELECT * FROM designer_attachment_submission WHERE id=#{id}")
    Optional<DesignerAttachmentSubmissionRow> findDesignerAttachmentSubmission(String id);

    @Update("""
            UPDATE designer_attachment_submission SET state=#{state},old_external_session_id=#{oldExternalSessionId},
              new_external_session_id=#{newExternalSessionId},external_message_id=#{externalMessageId},
              error_code=#{errorCode},error_detail=#{errorDetail},updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version}
            """)
    int updateDesignerAttachmentSubmission(DesignerAttachmentSubmissionRow row);

    @Insert("""
            INSERT INTO designer_attachment(
              id,designer_session_id,designer_message_id,submission_id,scope_key,work_package_id,
              original_filename,detected_media_type,size_bytes,sha256,relative_path,extractor_id,
              extractor_version,extracted_media_type,extracted_size_bytes,extracted_sha256,
              extracted_relative_path,preview_kind,state,superseded_by_attachment_id,sent_at,stopped_at,
              created_at,updated_at,version)
            VALUES(#{id},#{designerSessionId},#{designerMessageId},#{submissionId},#{scopeKey},#{workPackageId},
              #{originalFilename},#{detectedMediaType},#{sizeBytes},#{sha256},#{relativePath},#{extractorId},
              #{extractorVersion},#{extractedMediaType},#{extractedSizeBytes},#{extractedSha256},
              #{extractedRelativePath},#{previewKind},#{state},#{supersededByAttachmentId},#{sentAt},#{stoppedAt},
              #{createdAt},#{updatedAt},#{version})
            """)
    int insertDesignerAttachment(DesignerAttachmentRow row);

    @Select("SELECT * FROM designer_attachment WHERE id=#{id}")
    Optional<DesignerAttachmentRow> findDesignerAttachment(String id);

    @Select("SELECT * FROM designer_attachment WHERE designer_session_id=#{sessionId} ORDER BY created_at,id")
    List<DesignerAttachmentRow> listDesignerAttachments(String sessionId);

    @Select("SELECT * FROM designer_attachment WHERE designer_message_id=#{messageId} ORDER BY created_at,id")
    List<DesignerAttachmentRow> listDesignerAttachmentsForMessage(String messageId);

    @Select("SELECT * FROM designer_attachment WHERE submission_id=#{submissionId} ORDER BY created_at,id")
    List<DesignerAttachmentRow> listDesignerAttachmentsForSubmission(String submissionId);

    @Select("""
            SELECT * FROM designer_attachment
            WHERE designer_session_id=#{sessionId} AND scope_key=#{scopeKey}
              AND original_filename=#{filename} COLLATE BINARY AND state='ACTIVE'
            LIMIT 1
            """)
    Optional<DesignerAttachmentRow> findActiveDesignerAttachmentByName(
            @Param("sessionId") String sessionId, @Param("scopeKey") String scopeKey,
            @Param("filename") String filename);

    @Select("""
            SELECT * FROM designer_attachment WHERE designer_session_id=#{sessionId} AND state='ACTIVE'
            ORDER BY CASE WHEN scope_key='REQUIREMENT' THEN 0 ELSE 1 END,scope_key,original_filename,id
            """)
    List<DesignerAttachmentRow> listActiveDesignerAttachments(String sessionId);

    @Select("SELECT COALESCE(SUM(size_bytes),0) FROM designer_attachment WHERE designer_session_id=#{sessionId}")
    long sumDesignerAttachmentBytes(String sessionId);

    @Update("""
            UPDATE designer_attachment SET state='SUPERSEDED',superseded_by_attachment_id=#{replacementId},
              updated_at=#{updatedAt},version=version+1 WHERE id=#{id} AND version=#{version} AND state='ACTIVE'
            """)
    int supersedeDesignerAttachment(@Param("id") String id, @Param("replacementId") String replacementId,
                                    @Param("updatedAt") String updatedAt, @Param("version") long version);

    @Update("""
            UPDATE designer_attachment SET superseded_by_attachment_id=#{replacementId},updated_at=#{updatedAt}
            WHERE id=#{id} AND state='SUPERSEDED' AND superseded_by_attachment_id IS NULL
            """)
    int bindDesignerAttachmentReplacement(@Param("id") String id, @Param("replacementId") String replacementId,
                                          @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE designer_attachment SET state='STOPPED',stopped_at=#{stoppedAt},updated_at=#{stoppedAt},
              version=version+1 WHERE id=#{id} AND version=#{version} AND state='ACTIVE'
            """)
    int stopDesignerAttachment(@Param("id") String id, @Param("stoppedAt") String stoppedAt,
                               @Param("version") long version);

    @Update("""
            UPDATE designer_attachment SET state='FROZEN',updated_at=#{updatedAt},version=version+1
            WHERE id=#{id} AND version=#{version} AND state='ACTIVE'
            """)
    int freezeDesignerAttachment(@Param("id") String id, @Param("updatedAt") String updatedAt,
                                 @Param("version") long version);

    @Insert("""
            INSERT INTO task_design_attachment(
              id,task_id,source_designer_attachment_id,source_task_id,original_filename,scope_key,work_package_id,
              detected_media_type,size_bytes,sha256,relative_path,extractor_id,extractor_version,
              extracted_media_type,extracted_size_bytes,extracted_sha256,extracted_relative_path,frozen_at)
            VALUES(#{id},#{taskId},#{sourceDesignerAttachmentId},#{sourceTaskId},#{originalFilename},#{scopeKey},
              #{workPackageId},#{detectedMediaType},#{sizeBytes},#{sha256},#{relativePath},#{extractorId},
              #{extractorVersion},#{extractedMediaType},#{extractedSizeBytes},#{extractedSha256},
              #{extractedRelativePath},#{frozenAt})
            """)
    int insertTaskDesignAttachment(TaskDesignAttachmentRow row);

    @Select("SELECT * FROM task_design_attachment WHERE task_id=#{taskId} ORDER BY scope_key,original_filename,id")
    List<TaskDesignAttachmentRow> listTaskDesignAttachments(String taskId);

    @Select("SELECT * FROM task_design_attachment WHERE id=#{id}")
    Optional<TaskDesignAttachmentRow> findTaskDesignAttachment(String id);
}
