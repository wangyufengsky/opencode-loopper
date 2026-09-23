package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceDevelopmentPromotionMapper {
    @Select("SELECT * FROM source_development_promotion WHERE run_id=#{run}")
    Optional<Promotion> promotion(String run);
    @Insert("""
        INSERT INTO source_development_promotion VALUES(
          #{sourceRevisionId},#{runId},#{designerId},#{targetRevisionId},#{profileJson},#{createdAt})
        """)
    int insertPromotion(Promotion row);
    record Promotion(String sourceRevisionId, String runId, String designerId, String targetRevisionId,
                     String profileJson, String createdAt) { }
}
