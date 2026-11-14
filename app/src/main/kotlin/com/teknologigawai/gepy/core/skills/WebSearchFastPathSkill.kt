package com.teknologigawai.gepy.core.skills

import com.teknologigawai.gepy.core.ports.ShortTermMemorySnapshot
import com.teknologigawai.gepy.features.brain.models.ReActActionModel
import com.teknologigawai.gepy.features.perception.models.PrunedUiElement

/**
 * Plugin *Fast-Path Skill* untuk mendeteksi perintah suara pencarian informasi web terkini
 * (Google Search Grounding) dan pembacaan URL halaman web (`r.jina.ai`) secara instan
 * tanpa perlu membuka aplikasi browser di layar.
 */
class WebSearchFastPathSkill : GepySkillPlugin {

    companion object {
        /** Pola perintah eksplisit yang meminta aksi di dalam aplikasi Android atau membaca layar (bukan pencarian web murni). */
        private val androidAppActionExclusionRegex = Regex(
            """\b(buka\s+aplikasi|di\s+whatsapp|di\s+wa|kirim\s+wa|kirim\s+pesan|kirim\s+chat|kirim\s+dm|di\s+instagram|di\s+ig|di\s+youtube|di\s+tiktok|di\s+shopee|di\s+tokopedia|di\s+gojek|di\s+grab|di\s+telegram|pasang\s+alarm|setel\s+alarm|atur\s+alarm|buat\s+alarm|bikin\s+alarm|alarm\s+pagi|alarm\s+subuh|alarm\s+sahur|baterai|notifikasi|kontak|nomor\s+telepon|navigasi\s+maps|rute\s+ke|di\s+layar|isi\s+layar|baca\s+layar|layar\s+ini|layar\s+sekarang|tombol\s+ini)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Perintah aksi OS API-First (Batch 1 + 2) agar tidak tertangkap jalur web search ketika bukan pertanyaan informasi umum. */
        private val systemApiActionExclusionRegex = Regex(
            """\b(nyala(?:in|kan)\s+(?:lampu\s+)?senter|mati(?:in|kan)\s+(?:lampu\s+)?senter|hidup(?:in|kan)\s+(?:lampu\s+)?senter|padamkan\s+senter|(?:pasang|set|setel|stel|atur|buat|bikin)\s+timer|(?:cek|lihat|daftar|list|tampilkan|buka)\s+alarm|naik(?:in|kan)\s+volume|besarin\s+volume|turun(?:in|kan)\s+volume|kecil(?:in|kan)\s+volume|(?:set|setel|atur)\s+volume|mode\s+(?:getar|normal|senyap|hening)|panel\s+(?:wifi|wi-fi|internet|volume|suara|nfc)|balas\s+(?:notif|notifikasi|pesan)|hapus\s+(?:notif|notifikasi)|bersihkan\s+(?:notif|notifikasi)|lagu\s+apa|judul\s+lagu|(?:kirim|buat|tulis|bikin)\s+(?:e-?mail|surel)|telepon\s+\w+|telpon\s+\w+)\b""",
            RegexOption.IGNORE_CASE
        )

        private val explanatoryQuestionCueRegex = Regex(
            """\b(cara(?:nya)?|bagaimana|gimana\s+cara|mengapa|kenapa|apa\s+itu|apa\s+maksud|apa\s+fungsi|fungsi\s+dari|apa\s+beda(?:nya)?|perbedaan|tutorial|pengertian|definisi)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Pola pertanyaan fakta terkini / berita / kurs / cuaca / olahraga / pencarian web. */
        private val realtimeWebQueryRegex = Regex(
            """\b(berita\s+terkini|berita\s+hari\s+ini|berita\s+terbaru|ada\s+berita\s+apa|kurs\s+dollar|kurs\s+dolar|kurs\s+rupiah|kurs\s+euro|kurs\s+yen|harga\s+emas|harga\s+bitcoin|harga\s+saham|prakiraan\s+cuaca|cuaca\s+hari\s+ini|cuaca\s+besok|cuaca\s+di\s+\w+|skor\s+pertandingan|skor\s+bola|hasil\s+pertandingan|jadwal\s+pertandingan|jadwal\s+sholat|jadwal\s+imsak|cari(?:kan)?\s+(?:di\s+)?(?:google|internet|web|online)|cari(?:kan)?\s+(?:tahu|info|informasi|berita|artikel|data|fakta)(?:\s+tentang)?|(?:tolong\s+|coba\s+)?cari(?:kan)?\s+tentang|browsing|googling|huggingface|hugging\s+face|github|git\s+hub|model\s+trending|paper\s+ai|siapa\s+(?:nama\s+)?(?:presiden|wakil\s+presiden|menteri|gubernur|bupati|wali\s+kota|pemimpin|pendiri|penemu|ceo)|apa\s+itu\s+\w+|apa\s+arti\s+\w+|apa\s+kepanjangan\s+\w+|ibu\s+kota\s+\w+|berapa\s+(?:kurs|harga|tinggi|jarak|jumlah\s+penduduk|umur|usia))\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Pola pertanyaan pengetahuan luas, sains, sejarah, tutorial, komparasi, dan rekomendasi
         * (v5.5.0 Broad Agentic Intelligence) agar dijawab secara cerdas via Web Intelligence tanpa membajak layar HP.
         */
        private val broadKnowledgeAndExplanationRegex = Regex(
            """\b(?:mengapa|kenapa|gimana\s+sih|bagaimana\s+cara|gimana\s+cara|cara\s+(?:membuat|memasak|memperbaiki|merawat|mengatasi|instalasi|install|menggunakan|belajar|daftar|menghitung|setting|kerja|nyalain|menyalakan|matiin|mematikan|mengatur)|apa\s+perbedaan|perbedaan\s+antara|beda\s+antara|mana\s+yang\s+lebih\s+bagus|bandingkan|kelebihan\s+dan\s+kekurangan|rekomendasi|saran\s+terbaik|tips\s+dan\s+trik|tips|review|ulasan|apa\s+penyebab|penyebab\s+\w+|alasan\s+\w+|apa\s+fungsi(?:\s+dari)?|fungsi\s+dari|manfaat\s+dari|kegunaan\s+dari|sejarah\s+\w+|asal\s+usul\s+\w+|jelaskan\s+(?:tentang|mengenai)?\s+\w+|apa\s+maksud\s+dari)\b""",
            RegexOption.IGNORE_CASE
        )

        private val conversationalPrefixRegex = Regex(
            """^(?:halo\s+|hai\s+|hei\s+)?(?:gepy\s+)?(?:tolong\s+|coba\s+|bantu\s+|bisa\s+|minta\s+tolong\s+)?(?:cari(?:kan)?\s+(?:tahu\s+|info\s+|informasi\s+|berita\s+|data\s+)?(?:di\s+(?:internet|google|web|online)\s*)?|browsing\s+(?:di\s+internet\s*)?|googling\s*|jelaskan\s+(?:tentang\s+|mengenai\s+)?|ceritakan\s+(?:tentang\s+|mengenai\s+)?)(?:tentang\s+|mengenai\s+|soal\s+|apa\s+itu\s+)?""",
            RegexOption.IGNORE_CASE
        )

        /** Pola pembacaan URL langsung (contoh: "bacakan https://kompas.com/..." atau "rangkum halaman kompas.com/..."). */
        private val urlExtractRegex = Regex(
            """(https?://[^\s"'<>]+)""",
            RegexOption.IGNORE_CASE
        )

        private val bareDomainExtractRegex = Regex(
            """\b((?:www\.)?[a-z0-9-]+(?:\.(?:com|id|co\.id|go\.id|ac\.id|or\.id|org|net|io|ai|edu|gov))(?:/[^\s"'<>]*)?)""",
            RegexOption.IGNORE_CASE
        )

        private val readUrlIntentRegex = Regex(
            """\b(baca\s+artikel|bacakan\s+artikel|baca\s+web|bacakan\s+web|baca\s+halaman|bacakan\s+halaman|baca\s+link|baca\s+url|rangkum\s+web|rangkum\s+artikel|rangkum\s+halaman)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Memeriksa apakah transkrip suara pengguna merupakan pertanyaan informasi web murni
         * atau perintah membaca URL yang sebaiknya dijawab langsung oleh `WebSearchAndReaderBridge`.
         */
        fun isPureWebInformationQuery(transcript: String): Boolean {
            val clean = transcript.trim()
            if (clean.length < 5) return false
            if (androidAppActionExclusionRegex.containsMatchIn(clean)) {
                return false
            }
            if (!explanatoryQuestionCueRegex.containsMatchIn(clean) &&
                systemApiActionExclusionRegex.containsMatchIn(clean)
            ) {
                return false
            }
            if (extractTargetUrl(clean) != null && readUrlIntentRegex.containsMatchIn(clean)) {
                return true
            }
            return realtimeWebQueryRegex.containsMatchIn(clean) || broadKnowledgeAndExplanationRegex.containsMatchIn(clean)
        }

        /**
         * Membersihkan awalan perintah percakapan (seperti "Gepy tolong carikan di internet tentang...")
         * agar kueri yang dikirim ke mesin pencari fokus pada topik utamanya.
         */
        fun extractCleanSearchQuery(transcript: String): String {
            val trimmed = transcript.trim()
            val stripped = conversationalPrefixRegex.replaceFirst(trimmed, "").trim()
                .trim('?', '.', ',', '!', '"', '\'')
                .trim()
            return if (stripped.length >= 2) {
                stripped
            } else {
                "berita utama Indonesia hari ini"
            }
        }

        /** Mengekstrak URL `http://`, `https://`, atau domain populer dari transkrip jika tersedia. */
        fun extractTargetUrl(transcript: String): String? {
            val direct = urlExtractRegex.find(transcript)?.value?.trimEnd('.', ',', ';', ')')
            if (!direct.isNullOrBlank()) {
                return direct
            }
            if (readUrlIntentRegex.containsMatchIn(transcript)) {
                val bare = bareDomainExtractRegex.find(transcript)?.value?.trimEnd('.', ',', ';', ')')
                if (!bare.isNullOrBlank()) {
                    return "https://$bare"
                }
            }
            return null
        }
    }

    override val skillId: String = "web_search_and_reader"

    override val supportedPackages: List<String> = emptyList()

    override fun canHandleVoiceIntent(transcript: String): Boolean {
        return isPureWebInformationQuery(transcript)
    }

    override val domainKnowledgePrompt: String =
        """
[DOMAIN SKILL: REALTIME WEB SEARCH & JINA READER]
Untuk pertanyaan fakta terkini (berita, kurs, cuaca, skor olahraga, jadwal) atau pembacaan artikel URL, gunakan hasil pencarian web terverifikasi dan sampaikan dalam 2-3 kalimat Bahasa Indonesia lisan yang alami.
        """.trimIndent()

    override fun proposeFastPathAction(
        userGoal: String,
        foregroundPackage: String,
        screenElements: List<PrunedUiElement>,
        memory: ShortTermMemorySnapshot
    ): ReActActionModel? {
        // Eksekusi jaringan asinkron ditangani langsung pada Langkah 0D di GepyOrchestrator
        return null
    }
}
