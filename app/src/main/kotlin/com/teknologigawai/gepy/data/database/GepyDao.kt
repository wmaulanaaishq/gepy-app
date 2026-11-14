package com.teknologigawai.gepy.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Data Access Object (DAO) Room untuk keempat tabel inti `gepy_core.db`. */
@Dao
interface GepyDao {

    @Query("SELECT package_name FROM app_package_cache WHERE alias_name = :alias LIMIT 1")
    suspend fun lookupPackageAlias(alias: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackageAlias(entity: AppPackageCacheEntity)

    @Query(
        """
        SELECT * FROM contact_disambiguation_cache
        WHERE query_name = :queryName
          AND package_name = :packageName
          AND resolved_display_name = :resolvedDisplayName
        LIMIT 1
        """
    )
    suspend fun findContactPreference(
        queryName: String,
        packageName: String,
        resolvedDisplayName: String
    ): ContactDisambiguationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContactPreference(entity: ContactDisambiguationEntity)

    @Query(
        """
        SELECT * FROM contact_disambiguation_cache
        WHERE query_name = :queryName AND package_name = :packageName
        """
    )
    suspend fun getContactPreferencesForQuery(
        queryName: String,
        packageName: String
    ): List<ContactDisambiguationEntity>

    @Query(
        """
        SELECT * FROM app_knowledge_graph
        WHERE package_name = :packageName
          AND failure_count = 0
          AND is_disabled = 0
        ORDER BY success_count DESC
        LIMIT 5
        """
    )
    suspend fun getTopKnowledgeForPackage(packageName: String): List<AppKnowledgeGraphEntity>

    @Query("SELECT * FROM app_knowledge_graph ORDER BY updated_at DESC, id DESC LIMIT 100")
    suspend fun getAllKnowledgeEntries(): List<AppKnowledgeGraphEntity>

    @Query("DELETE FROM app_knowledge_graph WHERE id = :id")
    suspend fun deleteKnowledgeById(id: Long)

    @Query("DELETE FROM app_knowledge_graph")
    suspend fun deleteAllKnowledge()

    @Query(
        """
        UPDATE app_knowledge_graph
        SET failure_count = failure_count + 1,
            is_disabled = 1,
            updated_at = :nowMs
        WHERE last_session_id = :sessionId
        """
    )
    suspend fun disableSessionKnowledge(sessionId: String, nowMs: Long)

    @Query(
        """
        SELECT * FROM app_knowledge_graph
        WHERE package_name = :packageName
          AND screen_signature_hash = :screenSignatureHash
          AND subgoal_intent = :subgoalIntent
          AND target_resource_id = :targetResourceId
          AND action_type = :actionType
        LIMIT 1
        """
    )
    suspend fun findKnowledgeEntry(
        packageName: String,
        screenSignatureHash: String,
        subgoalIntent: String,
        targetResourceId: String,
        actionType: String
    ): AppKnowledgeGraphEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeEntry(entity: AppKnowledgeGraphEntity)

    @Query(
        """
        SELECT * FROM app_knowledge_graph
        WHERE package_name = :packageName
          AND screen_signature_hash = :screenSignatureHash
          AND subgoal_intent = :subgoalIntent
          AND success_count >= :minSuccessCount
          AND failure_count = 0
          AND is_disabled = 0
        ORDER BY success_count DESC
        LIMIT 1
        """
    )
    suspend fun findBestPlaybookTransition(
        packageName: String,
        screenSignatureHash: String,
        subgoalIntent: String,
        minSuccessCount: Int
    ): AppKnowledgeGraphEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryLog(entity: TelemetryLogEntity)

    @Query("SELECT * FROM telemetry_logs ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun getRecentTelemetryLogs(limit: Int): List<TelemetryLogEntity>

    @Query("SELECT * FROM telemetry_logs ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeRecentTelemetryLogs(limit: Int = 50): Flow<List<TelemetryLogEntity>>

    // --- Room v3: Episodic Self-Learning Lessons ---

    @Query(
        """
        SELECT * FROM episodic_lessons
        WHERE package_name = :packageName
        ORDER BY hit_count DESC, updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getEpisodicLessonsForPackage(packageName: String, limit: Int = 3): List<EpisodicLessonEntity>

    @Query(
        """
        SELECT * FROM episodic_lessons
        WHERE package_name = :packageName AND lesson_rule = :lessonRule
        LIMIT 1
        """
    )
    suspend fun findEpisodicLesson(packageName: String, lessonRule: String): EpisodicLessonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodicLesson(entity: EpisodicLessonEntity)

    // --- Room v3: Personal Wellness & CSI Logs ---

    @Query("SELECT * FROM personal_wellness_logs WHERE date_key = :dateKey LIMIT 1")
    suspend fun getWellnessLogForDate(dateKey: String): PersonalWellnessLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWellnessLog(entity: PersonalWellnessLogEntity)

    // --- Room v4: Sovereign Temporal Knowledge Graph ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemporalFact(entity: TemporalMemoryFactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemporalFacts(entities: List<TemporalMemoryFactEntity>): List<Long>

    @Query(
        """
        SELECT * FROM temporal_memory_facts
        WHERE is_active = 1
          AND (valid_until_ms IS NULL OR valid_until_ms > :nowMs)
        ORDER BY hit_count DESC, updated_at_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getActiveTemporalFacts(nowMs: Long, limit: Int = 50): List<TemporalMemoryFactEntity>

    @Query(
        """
        SELECT * FROM temporal_memory_facts
        WHERE subject = :subject AND predicate = :predicate AND is_active = 1
        """
    )
    suspend fun findActiveFactsBySubjectPredicate(
        subject: String,
        predicate: String
    ): List<TemporalMemoryFactEntity>

    @Query(
        """
        UPDATE temporal_memory_facts
        SET is_active = 0,
            valid_until_ms = :nowMs,
            superseded_by_id = :newId,
            updated_at_ms = :nowMs
        WHERE id = :oldId
        """
    )
    suspend fun supersedeTemporalFact(oldId: Long, newId: Long, nowMs: Long)

    @Query(
        """
        UPDATE temporal_memory_facts
        SET is_active = 0,
            updated_at_ms = :nowMs
        WHERE is_active = 1
          AND valid_until_ms IS NOT NULL
          AND valid_until_ms <= :nowMs
        """
    )
    suspend fun expireOutdatedTemporalFacts(nowMs: Long): Int

    @Query(
        """
        SELECT * FROM temporal_memory_facts
        WHERE subject LIKE '%' || :query || '%'
           OR predicate LIKE '%' || :query || '%'
           OR object_value LIKE '%' || :query || '%'
           OR fact_text LIKE '%' || :query || '%'
        ORDER BY created_at_ms DESC
        LIMIT :limit
        """
    )
    suspend fun searchTemporalTimeline(query: String, limit: Int = 30): List<TemporalMemoryFactEntity>

    @Query("SELECT * FROM temporal_memory_facts ORDER BY is_active DESC, updated_at_ms DESC LIMIT :limit")
    suspend fun getAllTemporalFacts(limit: Int = 1000): List<TemporalMemoryFactEntity>

    @Query("SELECT * FROM temporal_memory_facts ORDER BY is_active DESC, updated_at_ms DESC LIMIT :limit")
    fun observeAllTemporalFacts(limit: Int = 1000): Flow<List<TemporalMemoryFactEntity>>

    @Query("UPDATE temporal_memory_facts SET hit_count = hit_count + 1, updated_at_ms = :nowMs WHERE id = :id")
    suspend fun incrementTemporalFactHitCount(id: Long, nowMs: Long)

    @Query("SELECT COUNT(*) FROM temporal_memory_facts")
    suspend fun countTemporalFacts(): Int

    @Query("DELETE FROM temporal_memory_facts WHERE id = :id")
    suspend fun deleteTemporalFactById(id: Long)

    @Query("DELETE FROM temporal_memory_facts")
    suspend fun deleteAllTemporalFacts()

    // --- Room v5: Layer 2 Semantic Vector Memory Index ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryVector(entity: MemoryVectorEntity): Long

    @Query("SELECT * FROM memory_vectors WHERE fact_id = :factId LIMIT 1")
    suspend fun findVectorByFactId(factId: Long): MemoryVectorEntity?

    @Query("SELECT * FROM memory_vectors WHERE content_hash = :contentHash LIMIT 1")
    suspend fun findVectorByContentHash(contentHash: String): MemoryVectorEntity?

    @Query("SELECT * FROM memory_vectors ORDER BY importance DESC, updated_at_ms DESC LIMIT :limit")
    suspend fun getRecentMemoryVectors(limit: Int = 1000): List<MemoryVectorEntity>

    @Query("SELECT content_hash FROM memory_vectors")
    suspend fun getAllVectorContentHashes(): List<String>

    @Query("SELECT * FROM memory_vectors ORDER BY importance DESC, updated_at_ms DESC LIMIT :limit OFFSET :offset")
    suspend fun getMemoryVectorsPaged(limit: Int, offset: Int): List<MemoryVectorEntity>

    @Query("SELECT COUNT(*) FROM memory_vectors")
    suspend fun countMemoryVectors(): Int

    @Query("DELETE FROM memory_vectors WHERE fact_id = :factId")
    suspend fun deleteVectorByFactId(factId: Long)

    @Query("DELETE FROM memory_vectors WHERE content_text LIKE '%' || :keyword || '%' OR subject LIKE '%' || :keyword || '%'")
    suspend fun deleteVectorsMatchingKeyword(keyword: String)

    @Query(
        """
        DELETE FROM memory_vectors
        WHERE id NOT IN (
            SELECT id FROM memory_vectors
            ORDER BY importance DESC, updated_at_ms DESC
            LIMIT :keepCount
        )
        """
    )
    suspend fun pruneExcessVectors(keepCount: Int = 2500): Int

    @Query("DELETE FROM memory_vectors")
    suspend fun deleteAllMemoryVectors()

    // --- Room v6: Symbolic Edge World Model (ui_transition_rules) ---

    @Query(
        """
        SELECT * FROM ui_transition_rules
        WHERE package_name = :packageName
          AND screen_signature_hash = :screenSignatureHash
          AND edge_key = :edgeKey
          AND action_type = :actionType
        LIMIT 1
        """
    )
    suspend fun findEdgeRule(
        packageName: String,
        screenSignatureHash: String,
        edgeKey: String,
        actionType: String
    ): UiTransitionRuleEntity?

    @Query(
        """
        SELECT * FROM ui_transition_rules
        WHERE package_name = :packageName
          AND screen_signature_hash = :screenSignatureHash
        ORDER BY risk_score DESC, updated_at_ms DESC
        LIMIT 30
        """
    )
    suspend fun getEdgeRulesForScreen(
        packageName: String,
        screenSignatureHash: String
    ): List<UiTransitionRuleEntity>

    @Query("SELECT * FROM ui_transition_rules ORDER BY risk_score DESC, updated_at_ms DESC LIMIT :limit")
    suspend fun getTopEdgeRules(limit: Int = 2000): List<UiTransitionRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdgeRule(entity: UiTransitionRuleEntity): Long

    @Query("SELECT COUNT(*) FROM ui_transition_rules")
    suspend fun countEdgeRules(): Int

    @Query(
        """
        DELETE FROM ui_transition_rules
        WHERE id NOT IN (
            SELECT id FROM ui_transition_rules
            ORDER BY risk_score DESC, updated_at_ms DESC
            LIMIT :keepCount
        )
        """
    )
    suspend fun pruneExcessEdgeRules(keepCount: Int = 2000): Int

    @Query("DELETE FROM ui_transition_rules")
    suspend fun deleteAllEdgeRules()
}
