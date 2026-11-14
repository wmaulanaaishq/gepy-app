package com.teknologigawai.gepy.features.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ElevenLabsTtsClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `synthesizeToFile sends xi-api-key and voice_settings and writes MP3 atomically`() = runTest {
        var capturedPath = ""
        var capturedQuery = ""
        var capturedApiKey = ""
        var capturedBody = ""
        val fakeMp3Bytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x11, 0x22, 0x33, 0x44)

        val server = LoopbackHttpServer { req ->
            capturedPath = req.path
            capturedQuery = req.query
            capturedApiKey = req.headers["xi-api-key"].orEmpty()
            capturedBody = req.body.toString(Charsets.UTF_8)
            LoopbackHttpResponse(200, "audio/mpeg", fakeMp3Bytes)
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val destFile = File(tempFolder.root, "voice_gepy/sample.mp3")

            val result = client.synthesizeToFile(
                apiKey = "sk_test_dummy_key_12345",
                voiceId = "voice_gepy_id",
                text = "Halo sayang, semuanya udah beres ya.",
                destinationFile = destFile,
                modelId = "eleven_v4_turbo",
                languageCode = "id",
                stability = 0.45f,
                similarityBoost = 0.75f
            )

            assertTrue(result is ElevenLabsSynthResult.Success)
            val success = result as ElevenLabsSynthResult.Success
            assertEquals(fakeMp3Bytes.size.toLong(), success.bytesWritten)
            assertTrue(destFile.exists())
            assertArrayEquals(fakeMp3Bytes, destFile.readBytes())
            assertFalse(File(destFile.parentFile, "${destFile.name}.tmp").exists())

            assertEquals("/v1/text-to-speech/voice_gepy_id", capturedPath)
            assertEquals("output_format=mp3_44100_64", capturedQuery)
            assertEquals("sk_test_dummy_key_12345", capturedApiKey)

            val parsed = JSONObject(capturedBody)
            assertEquals("Halo sayang, semuanya udah beres ya.", parsed.getString("text"))
            assertEquals("eleven_v4_turbo", parsed.getString("model_id"))
            assertEquals("id", parsed.getString("language_code"))
            val vs = parsed.getJSONObject("voice_settings")
            assertEquals(0.45, vs.getDouble("stability"), 0.01)
            assertEquals(0.75, vs.getDouble("similarity_boost"), 0.01)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `circuit breaker retries once on transient HTTP 429 then opens and recovers after backoff`() = runTest {
        val hitCounter = AtomicInteger(0)
        val fakeMp3 = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val server = LoopbackHttpServer {
            val callNum = hitCounter.incrementAndGet()
            if (callNum <= 2) {
                LoopbackHttpResponse(
                    429,
                    "application/json",
                    """{"detail":"quota_exceeded"}""".toByteArray(Charsets.UTF_8)
                )
            } else {
                LoopbackHttpResponse(200, "audio/mpeg", fakeMp3)
            }
        }
        server.start()

        try {
            var fakeNowMs = 1_000_000L
            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                clockMs = { fakeNowMs }
            )

            // 1. Panggilan pertama mendapat HTTP 429 + 1x retry otomatis (total 2 hit) -> membuka circuit breaker (20 detik)
            val first = client.synthesizeToFile(
                apiKey = "sk_test_key",
                voiceId = "gepy",
                text = "Tes pertama",
                destinationFile = File(tempFolder.root, "c1.mp3")
            )
            assertTrue(first is ElevenLabsSynthResult.Failure)
            assertEquals(429, (first as ElevenLabsSynthResult.Failure).httpStatus)
            assertTrue(client.isCircuitOpen)
            assertEquals(2, hitCounter.get())

            // 2. Panggilan kedua saat circuit terbuka langsung ditolak tanpa menyentuh HTTP server
            val second = client.synthesizeToFile(
                apiKey = "sk_test_key",
                voiceId = "gepy",
                text = "Tes kedua",
                destinationFile = File(tempFolder.root, "c2.mp3")
            )
            assertTrue(second is ElevenLabsSynthResult.Failure)
            assertTrue((second as ElevenLabsSynthResult.Failure).circuitOpened)
            assertEquals(2, hitCounter.get())

            // 3. Setelah waktu backoff lewat (> 20 detik), circuit pulih otomatis
            fakeNowMs += 25_000L
            assertFalse(client.isCircuitOpen)
            val third = client.synthesizeToFile(
                apiKey = "sk_test_key",
                voiceId = "gepy",
                text = "Tes ketiga",
                destinationFile = File(tempFolder.root, "c3.mp3")
            )
            assertTrue(third is ElevenLabsSynthResult.Success)
            assertEquals(3, hitCounter.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `fetchSubscriptionQuota parses remaining credits and tier`() = runTest {
        val server = LoopbackHttpServer {
            val json = """{"tier":"creator","character_count":16,"character_limit":40000}""".toByteArray(Charsets.UTF_8)
            LoopbackHttpResponse(200, "application/json", json)
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(baseUrl = "http://127.0.0.1:${server.port}/v1")
            val res = client.fetchSubscriptionQuota("sk_test_subscription_key")
            assertTrue(res.isSuccess)
            val info = res.getOrThrow()
            assertEquals(16, info.characterCount)
            assertEquals(40_000, info.characterLimit)
            assertEquals(39_984, info.remainingCredits)
            assertEquals("creator", info.tier)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `transient HTTP 503 and rate-limit 429 retry twice without opening global circuit on a single burst`() = runTest {
        val hitCounter = AtomicInteger(0)
        val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x77, 0x88.toByte())

        val server = LoopbackHttpServer {
            val hit = hitCounter.incrementAndGet()
            when {
                // Panggilan 1: 2x gagal transien 503/502, lalu hit ke-3 berhasil 200 OK
                hit in 1..2 -> LoopbackHttpResponse(503, "application/json", """{"detail":"busy"}""".toByteArray())
                hit == 3 -> LoopbackHttpResponse(200, "audio/mpeg", fakeMp3)
                // Panggilan 2: 3x rate-limit concurrency 429 -> gagal 1 panggilan tapi CIRCUIT TIDAK BOLEH TERBUKA
                hit in 4..6 -> LoopbackHttpResponse(429, "application/json", """{"detail":"too_many_concurrent_requests"}""".toByteArray())
                else -> LoopbackHttpResponse(200, "audio/mpeg", fakeMp3)
            }
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                transientFailureThreshold = 3,
                softCooldownMs = 8_000L,
                retryBackoffMs = listOf(10L, 20L),
                jitterMaxMs = 0L
            )

            // 1. Sukses pada percobaan ke-3 (2x retry backoff seluler)
            val recovered = client.synthesizeToFile(
                apiKey = "sk_test",
                voiceId = "gepy",
                text = "Klausa satu",
                destinationFile = File(tempFolder.root, "retry_recover.mp3")
            )
            assertTrue(recovered is ElevenLabsSynthResult.Success)
            assertEquals(3, hitCounter.get())
            assertFalse(client.isCircuitOpen)
            assertEquals(0, client.currentTransientFailureCount)

            // 2. Satu panggilan concurrency-429 gagal penuh tidak langsung membungkam kalimat berikutnya
            val burstFail = client.synthesizeToFile(
                apiKey = "sk_test",
                voiceId = "gepy",
                text = "Klausa dua",
                destinationFile = File(tempFolder.root, "burst_fail.mp3")
            )
            assertTrue(burstFail is ElevenLabsSynthResult.Failure)
            val f = burstFail as ElevenLabsSynthResult.Failure
            assertTrue(f.transient)
            assertFalse(f.circuitOpened)
            assertFalse(client.isCircuitOpen)
            assertEquals(1, client.currentTransientFailureCount)

            // 3. Panggilan berikutnya tetap menembak API dan me-reset counter kegagalan
            val nextOk = client.synthesizeToFile(
                apiKey = "sk_test",
                voiceId = "gepy",
                text = "Klausa tiga",
                destinationFile = File(tempFolder.root, "burst_next_ok.mp3")
            )
            assertTrue(nextOk is ElevenLabsSynthResult.Success)
            assertEquals(0, client.currentTransientFailureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `soft cooldown opens only after 3 consecutive transient failures and recovers after cooldown`() = runTest {
        val hitCounter = AtomicInteger(0)
        val allowSuccess = AtomicBoolean(false)
        val fakeMp3 = byteArrayOf(0x01, 0x02, 0x03)

        val server = LoopbackHttpServer {
            hitCounter.incrementAndGet()
            if (allowSuccess.get()) {
                LoopbackHttpResponse(200, "audio/mpeg", fakeMp3)
            } else {
                LoopbackHttpResponse(502, "text/plain", "Bad Gateway".toByteArray())
            }
        }
        server.start()

        try {
            var fakeNowMs = 5_000_000L
            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                clockMs = { fakeNowMs },
                transientFailureThreshold = 3,
                softCooldownMs = 8_000L,
                retryBackoffMs = listOf(5L, 5L),
                jitterMaxMs = 0L
            )

            // Kegagalan 1 & 2 belum membuka circuit
            repeat(2) { idx ->
                val res = client.synthesizeToFile("sk", "gepy", "T$idx", File(tempFolder.root, "t$idx.mp3"))
                assertTrue(res is ElevenLabsSynthResult.Failure)
                assertFalse((res as ElevenLabsSynthResult.Failure).circuitOpened)
                assertFalse(client.isCircuitOpen)
            }
            // Kegagalan beruntun ke-3 baru membuka soft cooldown (8 detik)
            val thirdFail = client.synthesizeToFile("sk", "gepy", "T2", File(tempFolder.root, "t2.mp3"))
            assertTrue(thirdFail is ElevenLabsSynthResult.Failure)
            assertTrue((thirdFail as ElevenLabsSynthResult.Failure).circuitOpened)
            assertTrue(client.isCircuitOpen)
            val hitsAtOpen = hitCounter.get()
            assertEquals(9, hitsAtOpen) // 3 panggilan x 3 percobaan

            // Saat cooldown masih aktif -> dilewati (0 hit tambahan)
            val skipped = client.synthesizeToFile("sk", "gepy", "T3", File(tempFolder.root, "t3.mp3"))
            assertTrue(skipped is ElevenLabsSynthResult.Failure)
            assertTrue((skipped as ElevenLabsSynthResult.Failure).circuitOpened)
            assertEquals(hitsAtOpen, hitCounter.get())

            // Setelah 8.5 detik -> soft cooldown pulih dan mengembalikan Success + reset counter
            fakeNowMs += 8_500L
            assertFalse(client.isCircuitOpen)
            allowSuccess.set(true)
            val recovered = client.synthesizeToFile("sk", "gepy", "T4", File(tempFolder.root, "t4.mp3"))
            assertTrue(recovered is ElevenLabsSynthResult.Success)
            assertEquals(0, client.currentTransientFailureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `coroutine barge-in cancellation aborts slow HTTP stream without poisoning circuit breaker`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)

        val server = LoopbackHttpServer(handleConcurrently = true) {
            requestStarted.countDown()
            releaseServer.await(2, TimeUnit.SECONDS)
            LoopbackHttpResponse(200, "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33))
        }
        server.start()

        try {
            val client = ElevenLabsTtsClient(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                retryBackoffMs = listOf(10L),
                jitterMaxMs = 0L
            )
            val destFile = File(tempFolder.root, "cancelled.mp3")

            val job = launch(Dispatchers.IO) {
                client.synthesizeToFile(
                    apiKey = "sk_cancel",
                    voiceId = "gepy",
                    text = "Kalimat panjang yang disela pengguna",
                    destinationFile = destFile
                )
            }
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
            job.cancelAndJoin()
            releaseServer.countDown()

            assertEquals(0, client.currentTransientFailureCount)
            assertFalse(client.isCircuitOpen)
            assertFalse(destFile.exists())
        } finally {
            releaseServer.countDown()
            server.stop()
        }
    }
}

internal data class LoopbackHttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

internal data class LoopbackHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray
)

internal class LoopbackHttpServer(
    private val handleConcurrently: Boolean = false,
    private val handler: (LoopbackHttpRequest) -> LoopbackHttpResponse
) {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val workerExecutor: ExecutorService? = if (handleConcurrently) {
        Executors.newCachedThreadPool { r -> Thread(r, "gepy-test-http-worker").apply { isDaemon = true } }
    } else {
        null
    }

    val port: Int get() = serverSocket.localPort

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                    if (!running.get()) break else continue
                }
                if (workerExecutor != null) {
                    workerExecutor.execute { serveClientSocket(client) }
                } else {
                    serveClientSocket(client)
                }
            }
        }, "gepy-test-loopback-http").apply {
            isDaemon = true
            start()
        }
    }

    private fun serveClientSocket(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val headerBuf = ByteArrayOutputStream()
                var state = 0 // tracks \r\n\r\n
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    headerBuf.write(b)
                    state = when (state) {
                        0 -> if (b == '\r'.code) 1 else 0
                        1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                        2 -> if (b == '\r'.code) 3 else 0
                        3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                        else -> 0
                    }
                    if (state == 4) break
                }
                val headerText = headerBuf.toString(Charsets.UTF_8.name())
                val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                val requestLine = lines.firstOrNull().orEmpty().split(" ")
                val method = requestLine.getOrElse(0) { "GET" }
                val rawTarget = requestLine.getOrElse(1) { "/" }
                val qIdx = rawTarget.indexOf('?')
                val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
                val query = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

                val headers = mutableMapOf<String, String>()
                for (line in lines.drop(1)) {
                    val sep = line.indexOf(':')
                    if (sep > 0) {
                        val k = line.substring(0, sep).trim().lowercase()
                        val v = line.substring(sep + 1).trim()
                        headers[k] = v
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(buf, offset, contentLength - offset)
                        if (read == -1) break
                        offset += read
                    }
                    if (offset == contentLength) buf else buf.copyOf(offset)
                } else {
                    ByteArray(0)
                }

                val response = handler(LoopbackHttpRequest(method, path, query, headers, bodyBytes))
                val statusText = if (response.statusCode == 200) "OK" else "Error"
                val respHead = "HTTP/1.1 ${response.statusCode} $statusText\r\n" +
                    "Content-Type: ${response.contentType}\r\n" +
                    "Content-Length: ${response.body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = socket.getOutputStream()
                out.write(respHead.toByteArray(Charsets.UTF_8))
                out.write(response.body)
                out.flush()
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        running.set(false)
        workerExecutor?.shutdownNow()
        try {
            serverSocket.close()
        } catch (_: Throwable) {
        }
    }
}
