package com.teknologigawai.gepy.features.brain.models

import org.json.JSONArray
import org.json.JSONObject

/** Jenis ruang aksi deterministik yang diizinkan untuk dieksekusi oleh otak ReAct Gepy. */
enum class AgentActionType(val wireName: String) {
    /** Meluncurkan aplikasi secara langsung menggunakan resolusi `PackageManager` / `Intent`. */
    LAUNCH_APP("LAUNCH_APP"),

    /** Mengetuk elemen UI non-sensitif berdasarkan `target_index`. */
    CLICK("CLICK"),

    /** Menekan dan menahan (`800 ms`) elemen `target_index` untuk membuka menu konteks / reaksi / pilihan. */
    LONG_PRESS("LONG_PRESS"),

    /** Memfokuskan kolom `EDITABLE` dan menyuntikkan teks secara instan. */
    SET_TEXT("SET_TEXT"),

    /** Aksi komposit *AutoDroid*: memfokuskan kolom input pencarian, mengetik teks, dan langsung mengeksekusi Enter/Cari dalam 1 langkah. */
    SET_TEXT_AND_SUBMIT("SET_TEXT_AND_SUBMIT"),

    /** Menggulir layar ke arah konten atas (`UP`) atau bawah (`DOWN`). */
    SCROLL("SCROLL"),

    /** Menyapu layar 4 arah (`LEFT`, `RIGHT`, `UP`, `DOWN`) untuk *carousel* foto, tab horizontal, atau menggeser kartu. */
    SWIPE("SWIPE"),

    /** Menekan tombol navigasi Kembali tingkat global Android. */
    BACK("BACK"),

    /** Kembali ke layar Beranda Android (`GLOBAL_ACTION_HOME`). */
    HOME("HOME"),

    /** Menunggu pemuatan halaman/unggahan selama 2,5 detik (`waitStable`) tanpa mengetuk layar secara sia-sia. */
    WAIT("WAIT"),

    /** Menyalin teks dari elemen `target_index` atau `text_value` ke Clipboard OS & `scratchpad` untuk transfer lintas aplikasi. */
    CLIPBOARD_COPY("CLIPBOARD_COPY"),

    /** Menempelkan isi Clipboard OS langsung ke kolom input `EDITABLE` pada `target_index`. */
    CLIPBOARD_PASTE("CLIPBOARD_PASTE"),

    /** Meminta pengguna memilih satu opsi saat ditemukan entitas/kontak kembar. */
    ASK_USER_DISAMBIGUATION("ASK_USER_DISAMBIGUATION"),

    /** Meminta persetujuan *Human-in-the-Loop* sebelum mengetuk tombol ireversibel. */
    CONFIRM_AND_CLICK("CONFIRM_AND_CLICK"),

    /** Menandakan seluruh instruksi pengguna telah selesai dituntaskan. */
    DONE("DONE");

    companion object {
        fun fromWireName(raw: String?): AgentActionType {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return when (normalized) {
                "TYPE_AND_SUBMIT" -> SET_TEXT_AND_SUBMIT
                "HOLD", "LONG_CLICK" -> LONG_PRESS
                "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN" -> SWIPE
                "COPY_TO_CLIPBOARD", "COPY" -> CLIPBOARD_COPY
                "PASTE_CLIPBOARD", "PASTE" -> CLIPBOARD_PASTE
                "PRESS_HOME", "GO_HOME", "NAVIGATE_HOME" -> HOME
                "SLEEP", "DELAY" -> WAIT
                else -> entries.firstOrNull { it.wireName == normalized }
                    ?: throw IllegalArgumentException("Action LLM tidak dikenali: \"$raw\"")
            }
        }
    }
}

/** Klasifikasi evaluasi transisi layar langkah sebelumnya ala *Mobile-Agent-v2*. */
enum class ReflectionStatus(val wireName: String) {
    /** Langkah awal sesi sebelum ada aksi sebelumnya. */
    INITIAL("INITIAL"),

    /** Aksi sebelumnya berhasil mengubah layar sesuai harapan. */
    CORRECT("CORRECT"),

    /** Aksi sebelumnya tidak menghasilkan perubahan pada tanda tangan layar. */
    INEFFECTIVE("INEFFECTIVE"),

    /** Aksi sebelumnya membawa agen ke halaman yang keliru sehingga perlu kembali. */
    ERRONEOUS("ERRONEOUS");

    companion object {
        fun fromWireName(raw: String?): ReflectionStatus {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: INITIAL
        }
    }
}

/** Model terstruktur keluaran satu putaran penalaran *ReAct* dari `BrainPort`. */
data class ReActActionModel(
    val thought: String,
    val reflectionOnLastStep: ReflectionStatus,
    val action: AgentActionType,
    val expectedOutcome: String? = null,
    val saveToScratchpad: String? = null,
    val targetIndex: Int? = null,
    val appName: String? = null,
    val textValue: String? = null,
    val scrollDirection: String? = null,
    val questionTts: String? = null,
    val options: List<String> = emptyList(),
    val confirmationPrompt: String? = null,
    val completionTts: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("action", action.wireName)
        put("target_index", targetIndex ?: JSONObject.NULL)
        put("app_name", appName ?: JSONObject.NULL)
        put("text_value", textValue ?: JSONObject.NULL)
        put("scroll_direction", scrollDirection ?: JSONObject.NULL)
        put("question_tts", questionTts ?: JSONObject.NULL)
        put("options", JSONArray(options))
        put("confirmation_prompt", confirmationPrompt ?: JSONObject.NULL)
        put("completion_tts", completionTts ?: JSONObject.NULL)
        put("save_to_scratchpad", saveToScratchpad ?: JSONObject.NULL)
        put("reflection_on_last_step", reflectionOnLastStep.wireName)
        put("expected_outcome", expectedOutcome ?: JSONObject.NULL)
        put("thought", thought)
    }

    companion object {
        private val thinkBlockRegex = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        private val actionFieldRegex = Regex(""""action"\s*:\s*"([A-Za-z_]+)"""")
        private val targetIndexFieldRegex = Regex(""""target_index"\s*:\s*(\d+)""")
        private val reflectionFieldRegex = Regex(""""reflection_on_last_step"\s*:\s*"([A-Za-z_]+)"""")
        private val scrollDirFieldRegex = Regex(""""scroll_direction"\s*:\s*"(UP|DOWN|LEFT|RIGHT)"""", RegexOption.IGNORE_CASE)

        /**
         * Mengurai string mentah keluaran LLM (termasuk jika memiliki blok `<think>`,
         * terbungkus pagar kode markdown, atau terpotong pada field akhir `thought`) menjadi [ReActActionModel].
         */
        fun fromRawJsonString(rawResponse: String): ReActActionModel {
            var cleaned = rawResponse.replace(thinkBlockRegex, "").trim()
            if (cleaned.startsWith("```")) {
                val firstNewline = cleaned.indexOf('\n')
                if (firstNewline != -1) {
                    cleaned = cleaned.substring(firstNewline + 1)
                }
                if (cleaned.endsWith("```")) {
                    cleaned = cleaned.substring(0, cleaned.length - 3).trim()
                }
            }

            val startBrace = cleaned.indexOf('{')
            val endBrace = cleaned.lastIndexOf('}')
            if (startBrace != -1 && endBrace != -1 && endBrace > startBrace) {
                cleaned = cleaned.substring(startBrace, endBrace + 1)
            } else if (startBrace != -1) {
                cleaned = cleaned.substring(startBrace)
            }

            val jsonObj = try {
                JSONObject(cleaned)
            } catch (e: Exception) {
                trySalvageTruncatedJson(cleaned)?.let { return it }
                val conciseReason = (e.message ?: "Format JSON rusak")
                    .substringBefore(" of {")
                    .take(140)
                throw IllegalArgumentException(
                    "Respons LLM bukan objek JSON yang valid ($conciseReason, panjang=${cleaned.length} karakter).",
                    e
                )
            }
            return fromJsonObject(jsonObj)
        }

        /**
         * Penyelamat deterministik (*Truncated JSON Salvage Parser*):
         * Apabila respons LLM terpotong (`finish_reason = length`) pada bagian akhir (misal saat menulis `thought`),
         * tetapi `action` beserta parameter intinya sudah tertulis utuh di bagian atas JSON, pulihkan aksi tersebut
         * secara aman tanpa gagal dan tanpa RTT jaringan tambahan.
         */
        internal fun trySalvageTruncatedJson(partialJson: String): ReActActionModel? {
            val rawAction = actionFieldRegex.find(partialJson)?.groupValues?.getOrNull(1) ?: return null
            val parsedAction = try {
                AgentActionType.fromWireName(rawAction)
            } catch (_: Exception) {
                return null
            }

            val targetIdx = targetIndexFieldRegex.find(partialJson)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val appName = extractJsonStringField(partialJson, "app_name")
            val textVal = extractJsonStringField(partialJson, "text_value")
            val scrollDir = scrollDirFieldRegex.find(partialJson)?.groupValues?.getOrNull(1)?.uppercase()
            val confPrompt = extractJsonStringField(partialJson, "confirmation_prompt")
            val completionTts = extractJsonStringField(partialJson, "completion_tts")
            val scratchpad = extractJsonStringField(partialJson, "save_to_scratchpad")
            val expectedOutcome = extractJsonStringField(partialJson, "expected_outcome")
            val reflection = ReflectionStatus.fromWireName(
                reflectionFieldRegex.find(partialJson)?.groupValues?.getOrNull(1)
            )
            val completeThought = extractJsonStringField(partialJson, "thought")
            val salvagedThought = completeThought ?: extractPartialTrailingStringField(partialJson, "thought")
                ?.take(160)
                .orEmpty()

            val isValidCompleteAction = when (parsedAction) {
                AgentActionType.CLICK,
                AgentActionType.LONG_PRESS,
                AgentActionType.CLIPBOARD_PASTE,
                AgentActionType.CONFIRM_AND_CLICK -> targetIdx != null
                AgentActionType.CLIPBOARD_COPY -> targetIdx != null || !textVal.isNullOrEmpty()
                AgentActionType.SET_TEXT,
                AgentActionType.SET_TEXT_AND_SUBMIT -> targetIdx != null && !textVal.isNullOrEmpty()
                AgentActionType.LAUNCH_APP -> !appName.isNullOrBlank()
                AgentActionType.SCROLL,
                AgentActionType.SWIPE -> scrollDir != null
                AgentActionType.BACK,
                AgentActionType.HOME,
                AgentActionType.WAIT,
                AgentActionType.DONE -> true
                AgentActionType.ASK_USER_DISAMBIGUATION -> false
            }

            if (!isValidCompleteAction) return null

            return ReActActionModel(
                thought = salvagedThought.ifBlank { "Menjalankan aksi ${parsedAction.wireName}." },
                reflectionOnLastStep = reflection,
                action = parsedAction,
                expectedOutcome = expectedOutcome,
                saveToScratchpad = scratchpad,
                targetIndex = targetIdx,
                appName = appName,
                textValue = textVal,
                scrollDirection = scrollDir,
                confirmationPrompt = confPrompt,
                completionTts = completionTts
            )
        }

        private fun extractJsonStringField(source: String, key: String): String? {
            val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
            val raw = pattern.find(source)?.groupValues?.getOrNull(1) ?: return null
            val unescaped = raw
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\\\", "\\")
                .trim()
            return if (unescaped.isEmpty() || unescaped.equals("null", ignoreCase = true)) null else unescaped
        }

        private fun extractPartialTrailingStringField(source: String, key: String): String? {
            val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]*)$""")
            val raw = pattern.find(source)?.groupValues?.getOrNull(1)?.trim() ?: return null
            return raw.ifEmpty { null }
        }

        fun fromJsonObject(json: JSONObject): ReActActionModel {
            val parsedOptions = mutableListOf<String>()
            val rawOptions = json.optJSONArray("options")
            if (rawOptions != null) {
                for (i in 0 until rawOptions.length()) {
                    val item = rawOptions.optString(i, "").trim()
                    if (item.isNotEmpty() && !item.equals("null", ignoreCase = true)) {
                        parsedOptions.add(item)
                    }
                }
            }

            val parsedIndex: Int? = if (json.isNull("target_index")) {
                null
            } else {
                val rawVal = json.opt("target_index")
                when (rawVal) {
                    is Number -> rawVal.toInt()
                    is String -> rawVal.trim().toIntOrNull()
                    else -> null
                }
            }

            return ReActActionModel(
                thought = nullableNonEmptyString(json, "thought") ?: "",
                reflectionOnLastStep = ReflectionStatus.fromWireName(
                    nullableNonEmptyString(json, "reflection_on_last_step")
                ),
                expectedOutcome = nullableNonEmptyString(json, "expected_outcome"),
                saveToScratchpad = nullableNonEmptyString(json, "save_to_scratchpad"),
                action = AgentActionType.fromWireName(nullableNonEmptyString(json, "action")),
                targetIndex = parsedIndex,
                appName = nullableNonEmptyString(json, "app_name"),
                textValue = nullableNonEmptyString(json, "text_value"),
                scrollDirection = nullableNonEmptyString(json, "scroll_direction"),
                questionTts = nullableNonEmptyString(json, "question_tts"),
                options = parsedOptions,
                confirmationPrompt = nullableNonEmptyString(json, "confirmation_prompt"),
                completionTts = nullableNonEmptyString(json, "completion_tts")
            )
        }

        private fun nullableNonEmptyString(json: JSONObject, key: String): String? {
            if (!json.has(key) || json.isNull(key)) {
                return null
            }
            val text = json.optString(key, "").trim()
            if (text.isEmpty() || text.equals("null", ignoreCase = true)) {
                return null
            }
            return text
        }
    }
}
