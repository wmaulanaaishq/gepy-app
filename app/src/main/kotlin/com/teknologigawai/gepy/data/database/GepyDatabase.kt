package com.teknologigawai.gepy.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teknologigawai.gepy.core.config.EnvConfig

/**
 * Database lokal Room (`gepy_core.db`) untuk Gepy Native (`com.teknologigawai.gepy`).
 */
@Database(
    entities = [
        AppPackageCacheEntity::class,
        ContactDisambiguationEntity::class,
        AppKnowledgeGraphEntity::class,
        TelemetryLogEntity::class,
        EpisodicLessonEntity::class,
        PersonalWellnessLogEntity::class,
        TemporalMemoryFactEntity::class,
        MemoryVectorEntity::class,
        UiTransitionRuleEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class GepyDatabase : RoomDatabase() {

    abstract fun gepyDao(): GepyDao

    companion object {
        /**
         * Migrasi eksplisit `v1 -> v2`:
         * Mereset tabel `app_knowledge_graph` lama yang belum terverifikasi (*Two-Phase Commit*)
         * dan menambahkan kolom `failure_count`, `last_session_id`, `updated_at`, serta `is_disabled`,
         * sembari menjaga utuh preferensi kontak, alias paket, dan riwayat telemetri.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `app_knowledge_graph`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_knowledge_graph` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        `screen_signature_hash` TEXT NOT NULL,
                        `subgoal_intent` TEXT NOT NULL,
                        `target_resource_id` TEXT NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `success_count` INTEGER NOT NULL,
                        `failure_count` INTEGER NOT NULL,
                        `last_session_id` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `is_disabled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_knowledge_pkg` ON `app_knowledge_graph` (`package_name`)"
                )
            }
        }

        /**
         * Migrasi non-destruktif `v2 -> v3` (**Gepy v5.0 Sovereign Superintelligence**):
         * Menambahkan tabel `episodic_lessons` dan `personal_wellness_logs` tanpa menghapus data lama.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episodic_lessons` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        `goal_pattern` TEXT NOT NULL,
                        `lesson_rule` TEXT NOT NULL,
                        `hit_count` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_episodic_pkg` ON `episodic_lessons` (`package_name`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_wellness_logs` (
                        `date_key` TEXT NOT NULL PRIMARY KEY,
                        `step_count` INTEGER NOT NULL,
                        `social_scroll_seconds` INTEGER NOT NULL,
                        `sedentary_bed_minutes` INTEGER NOT NULL,
                        `interventions_count` INTEGER NOT NULL,
                        `last_csi_event` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migrasi non-destruktif `v3 -> v4` (**Gepy v5.1 Sovereign Temporal Knowledge Graph**):
         * Menambahkan tabel `temporal_memory_facts` dengan struktur triplet `(subject, predicate, object_value)`
         * dan masa berlaku temporal tanpa menghapus data tabel versi sebelumnya.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `temporal_memory_facts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `predicate` TEXT NOT NULL,
                        `object_value` TEXT NOT NULL,
                        `fact_text` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `valid_from_ms` INTEGER NOT NULL,
                        `valid_until_ms` INTEGER,
                        `is_active` INTEGER NOT NULL,
                        `superseded_by_id` INTEGER,
                        `hit_count` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `updated_at_ms` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_temporal_subject_active` ON `temporal_memory_facts` (`subject`, `is_active`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_temporal_valid_until` ON `temporal_memory_facts` (`valid_until_ms`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_temporal_category` ON `temporal_memory_facts` (`category`)"
                )
            }
        }

        /**
         * Migrasi non-destruktif `v4 -> v5` (**Gepy v5.7.0 — Layer 2 Semantic Vector Memory**):
         * Menambahkan tabel `memory_vectors` untuk indeks embedding lokal 768-dim (`Float32 BLOB`)
         * tanpa menghapus memori temporal pengguna sebelumnya.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_vectors` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fact_id` INTEGER,
                        `category` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `content_text` TEXT NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `embedding_blob` BLOB NOT NULL,
                        `model_id` TEXT NOT NULL,
                        `dimensions` INTEGER NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `updated_at_ms` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_vector_fact_id` ON `memory_vectors` (`fact_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_vector_hash` ON `memory_vectors` (`content_hash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_vector_category` ON `memory_vectors` (`category`)"
                )
            }
        }

        /**
         * Migrasi non-destruktif `v5 -> v6` (**Gepy v6.6.0 — Symbolic Edge World Model / SEWM**):
         * Menambahkan tabel `ui_transition_rules` (`< 1,5 MB` SQLite, 3 indeks komposit & LRU)
         * untuk memprediksi risiko transisi (`PAYWALL`, `AD_POPUP`, `PERMISSION_MODAL`, `ERROR_DIALOG`,
         * `INEFFECTIVE_DEADEND`) sebelum menyentuh tombol, tanpa menghapus seluruh tabel `v1..v5`.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ui_transition_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        `screen_signature_hash` TEXT NOT NULL,
                        `edge_key` TEXT NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `target_label_sample` TEXT NOT NULL,
                        `dominant_outcome` TEXT NOT NULL,
                        `safe_count` INTEGER NOT NULL,
                        `paywall_count` INTEGER NOT NULL,
                        `ad_popup_count` INTEGER NOT NULL,
                        `permission_modal_count` INTEGER NOT NULL,
                        `error_count` INTEGER NOT NULL,
                        `ineffective_count` INTEGER NOT NULL,
                        `risk_score` REAL NOT NULL,
                        `mitigation_hint` TEXT NOT NULL,
                        `updated_at_ms` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `idx_sewm_unique_edge` ON `ui_transition_rules` (`package_name`, `screen_signature_hash`, `edge_key`, `action_type`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_sewm_pkg_screen` ON `ui_transition_rules` (`package_name`, `screen_signature_hash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_sewm_updated` ON `ui_transition_rules` (`updated_at_ms`)"
                )
            }
        }

        @Volatile
        private var instance: GepyDatabase? = null

        fun getInstance(context: Context): GepyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GepyDatabase::class.java,
                    EnvConfig.sqliteDbFileName
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
