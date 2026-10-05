package com.mylo.browser.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeService(var configured_: Boolean = true, val reply: (List<AiTurn>, AiContext) -> Flow<AiEvent>) : MyloAiService {
    val requests = mutableListOf<Pair<List<AiTurn>, AiContext>>()
    override val configured get() = configured_
    override fun chat(conversationId: String, turns: List<AiTurn>, context: AiContext, private: Boolean): Flow<AiEvent> {
        requests += turns to context
        return reply(turns, context)
    }
    override suspend fun voiceSession(voice: String, private: Boolean) = throw AiException(AiProblem.NotConfigured)
}

private val page = PageContext("https://shop.example/plans?session=abc123", "Plans", "Basic \$5. Card on file 4111 1111 1111 1111. Call 555-123-4567.")

@OptIn(ExperimentalCoroutinesApi::class)
class AiConversationTest {
    private fun conversation(scope: TestScope, service: MyloAiService, board: AiSwitchboard = AiSwitchboard()) =
        AiConversation(service, board, scope, private = false, gather = { allowed ->
            Gathered(AiContext(page = page.takeIf { AiDataSource.CurrentPage in allowed }, tabs = if (AiDataSource.OtherTabs in allowed) listOf(page.copy(title = "Other")) else emptyList()))
        }, newId = { "conversation-1" })

    @Test fun withoutAServiceNothingIsSentAndItSaysSo() = runTest {
        val service = FakeService(configured_ = false) { _, _ -> fail("must not be called"); flow { } }
        val chat = conversation(this, service)
        assertTrue(chat.send("What is this page?"))
        advanceUntilIdle()
        val (question, answer) = chat.messages.value
        assertEquals(ChatMessage.State.NotSent, question.state)
        assertEquals(ChatMessage.State.NotSent, answer.state)
        assertEquals(AiProblem.NotConfigured.message, answer.note)
        assertTrue(service.requests.isEmpty())
        assertFalse(chat.send("   "))
    }

    @Test fun streamsTheAnswerWithAReceiptAndHidesSensitiveDetails() = runTest {
        val service = FakeService { _, _ -> flow { emit(AiEvent.Delta("Basic is ")); emit(AiEvent.Delta("\$5.")); emit(AiEvent.Done) } }
        val chat = conversation(this, service)
        chat.send("How much is Basic?")
        advanceUntilIdle()
        val answer = chat.messages.value.last()
        assertEquals("Basic is \$5.", answer.text)
        assertEquals(ChatMessage.State.Done, answer.state)
        val sent = service.requests.single().second
        assertEquals("https://shop.example/plans?session=abc123".let { Redactor.redact(it, AiPrivacy.alwaysHidden) }, sent.page!!.url)
        assertFalse(sent.page!!.text.contains("4111"))
        assertTrue("phone numbers on a page stay readable", sent.page!!.text.contains("555-123-4567"))
        assertEquals(PrivacyReceipt(setOf(AiDataSource.CurrentPage), 2), answer.receipt)
        assertTrue(answer.receipt!!.lines().contains("Other Tabs not shared"))
        assertFalse(chat.busy.value)
    }

    @Test fun theSwitchboardDecidesWhatIsRead() = runTest {
        val board = AiSwitchboard().apply { set(AiDataSource.CurrentPage, AiGrant.Off) }
        val service = FakeService { _, _ -> flow { emit(AiEvent.Done) } }
        val chat = conversation(this, service, board)
        chat.send("Hello")
        advanceUntilIdle()
        assertNull(service.requests.single().second.page)
        assertEquals(PrivacyReceipt(emptySet(), 0), chat.messages.value.last().receipt)
    }

    @Test fun asksBeforeReadingANeededSourceAndAllowOnceIsUsedUp() = runTest {
        val board = AiSwitchboard()
        val service = FakeService { _, _ -> flow { emit(AiEvent.Delta("Different prices.")); emit(AiEvent.Done) } }
        val chat = conversation(this, service, board)
        chat.send("Compare my tabs", needs = setOf(AiDataSource.CurrentPage, AiDataSource.OtherTabs))
        advanceUntilIdle()
        assertEquals(setOf(AiDataSource.OtherTabs), chat.messages.value.last().ask!!.missing)
        assertTrue(service.requests.isEmpty())
        chat.answerAsk(allowOnce = true)
        advanceUntilIdle()
        assertEquals(1, service.requests.single().second.tabs.size)
        assertEquals(AiGrant.Off, board.grant(AiDataSource.OtherTabs))
        assertTrue(chat.messages.value.first { it.ask != null }.ask!!.answered)
        chat.send("Compare again", needs = setOf(AiDataSource.OtherTabs))
        advanceUntilIdle()
        assertTrue("asks again: the one-time grant was used", chat.messages.value.last().ask != null)
        chat.answerAsk(allowOnce = false)
        advanceUntilIdle()
        assertEquals(1, service.requests.size)
        assertTrue(chat.messages.value.last().text.startsWith("Okay, I won't look"))
    }

    @Test fun stopKeepsWhatWasWrittenAndLaterQuestionsCarryTheConversation() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val service = FakeService { _, _ ->
            calls++
            if (calls == 1) flow { emit(AiEvent.Delta("Partial")); release.await(); emit(AiEvent.Delta(" never")); emit(AiEvent.Done) }
            else flow { emit(AiEvent.Delta("Second")); emit(AiEvent.Done) }
        }
        val chat = conversation(this, service)
        chat.send("First?")
        advanceUntilIdle()
        assertTrue(chat.busy.value)
        chat.stop()
        advanceUntilIdle()
        assertEquals("Partial", chat.messages.value.last().text)
        assertEquals(ChatMessage.State.Stopped, chat.messages.value.last().state)
        assertFalse(chat.busy.value)
        chat.answerLocally("Explain this page", "Open a web page first.")
        chat.send("Second?")
        advanceUntilIdle()
        assertEquals("local answers are never sent", listOf("First?", "Partial", "Second?"), service.requests.last().first.map { it.text })
        chat.clear()
        assertTrue(chat.messages.value.isEmpty())
    }

    @Test fun serviceProblemsAreExplained() = runTest {
        val service = FakeService { _, _ -> flow { throw AiException(AiProblem.Busy) } }
        val chat = conversation(this, service)
        chat.send("Hi")
        advanceUntilIdle()
        assertEquals(ChatMessage.State.Failed, chat.messages.value.last().state)
        assertEquals(AiProblem.Busy.message, chat.messages.value.last().note)
    }
}

class AiDeviceSourcesTest {
    @Test fun locationIsRoundedMemoryAndScreenshotsAreSharedOnlyWhenGathered() {
        val (prepared, _) = AiPrivacy.prepare(AiContext(location = -33.858712 to 151.215312, memory = listOf("Card 4111 1111 1111 1111"), screenshot = "/9j/AAAA"))
        assertEquals(-33.86 to 151.22, prepared.location)
        assertEquals(listOf("Card [card number]"), prepared.memory)
        assertEquals(setOf(AiDataSource.Location, AiDataSource.MyloMemory, AiDataSource.Screenshot), prepared.used)
        val json = JSONObject(AiContract.chatRequest("c", listOf(AiTurn(AiTurn.Role.User, "Hi")), prepared, false)).getJSONObject("context")
        assertEquals("/9j/AAAA", json.getJSONObject("screenshot").getString("data"))
        assertEquals(-33.86, json.getJSONObject("location").getDouble("lat"), 0.0)
        val receipt = PrivacyReceipt(setOf(AiDataSource.CurrentPage), 0, unavailable = setOf(AiDataSource.Location))
        assertTrue(receipt.lines().contains("Location allowed, but not available"))
        assertFalse(AiPrivacy.prepare(AiContext(screenshot = "x".repeat(AiContract.MAX_SCREENSHOT_CHARS + 1))).first.used.contains(AiDataSource.Screenshot))
    }
}

class AiContractTest {
    @Test fun chatRequestCarriesOnlyTheGivenContext() {
        val json = JSONObject(AiContract.chatRequest("c1", listOf(AiTurn(AiTurn.Role.User, "Hi")), AiContext(page = PageContext("https://a.example", "A", "x".repeat(30_000))), private = true))
        assertEquals("c1", json.getString("conversationId"))
        assertTrue(json.getBoolean("private"))
        val context = json.getJSONObject("context")
        assertEquals(AiContract.MAX_PAGE_CHARS, context.getJSONObject("page").getString("text").length)
        assertFalse(context.has("tabs"))
        assertFalse(context.has("history"))
        assertFalse(context.has("location"))
        assertEquals("user", json.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test fun readsServerSentEvents() {
        val reader = AiContract.SseReader()
        val lines = listOf(": keep-alive", "event: delta", "data: {\"text\":\"Hel\"}", "", "event: delta", "data: {\"text\":\"lo\"}", "",
            "event: citation", "data: {\"index\":1,\"title\":\"Plans\",\"url\":\"javascript:alert(1)\"}", "",
            "event: action", "data: {\"type\":\"submit_form\",\"target\":\"#buy\"}", "",
            "event: action", "data: {\"type\":\"scroll_to\",\"target\":\"Pricing\"}", "", "event: done", "data: {}", "")
        val events = lines.mapNotNull(reader::line)
        assertEquals(listOf(AiEvent.Delta("Hel"), AiEvent.Delta("lo"), AiEvent.Proposal("scroll_to", "Pricing", null), AiEvent.Done), events)
        assertEquals(AiEvent.Failure("invalid_response", "Mylo AI sent a reply this app couldn't read."), AiContract.parseEvent("delta", "{not json"))
    }

    @Test fun voiceSessionsMustBeShortLivedSecretsOverHttps() {
        val good = """{"provider":"openai-realtime","clientSecret":"ek_abc","expiresAt":2000,"model":"gpt-realtime","voice":"marin","webrtcUrl":"https://api.openai.com/v1/realtime/calls"}"""
        assertEquals("marin", AiContract.parseVoiceSession(good, 1000).voice)
        listOf(good.replace("ek_abc", "sk-live"), good.replace("https://api", "http://api"), good.replace("2000", "900")).forEach { bad ->
            try { AiContract.parseVoiceSession(bad, 1000); fail("accepted $bad") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun onlySafeServiceAddressesAreUsed() {
        assertEquals("https://ai.example.com", HttpMyloAiService.validatedBase("https://ai.example.com/", false))
        assertNull(HttpMyloAiService.validatedBase("http://ai.example.com", true))
        assertNull(HttpMyloAiService.validatedBase("http://10.0.2.2:8090", false))
        assertEquals("http://10.0.2.2:8090", HttpMyloAiService.validatedBase("http://10.0.2.2:8090", true))
        assertNull(HttpMyloAiService.validatedBase("https://user:pw@ai.example.com", false))
        assertNull(HttpMyloAiService.validatedBase("", false))
    }
}

class HttpMyloAiServiceTest {
    /** A tiny HTTP server on a socket (Android unit tests have no JDK HTTP server): path → status and body. */
    private class TinyServer(private val routes: Map<String, Pair<Int, String>>) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val seen = java.util.concurrent.ConcurrentHashMap<String, Pair<Map<String, String>, String>>()
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { connection ->
                    val input = java.io.BufferedInputStream(connection.getInputStream())
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) out.append(c.toChar()) }
                        return out.toString()
                    }
                    val path = line().split(" ").getOrElse(1) { "/" }
                    val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0).also { var read = 0; while (read < it.size) { val n = input.read(it, read, it.size - read); if (n < 0) break; read += n } }
                    seen[path] = headers to body.decodeToString()
                    val (status, reply) = routes[path] ?: (404 to "")
                    val bytes = reply.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() = socket.close()
    }

    @Test fun streamsFromTheServiceWithTheMyloToken() = runBlocking {
        TinyServer(mapOf(
            "/v1/chat" to (200 to "event: delta\ndata: {\"text\":\"Hi\"}\n\nevent: done\ndata: {}\n\n"),
            "/v1/voice/sessions" to (401 to ""),
        )).use { server ->
            val service = HttpMyloAiService({ AiEndpoint("http://127.0.0.1:${server.port}", "dev-token") }, allowDevCleartext = true)
            assertTrue(service.configured)
            val events = service.chat("c1", listOf(AiTurn(AiTurn.Role.User, "Hello")), AiContext(), private = false).toList()
            assertEquals(listOf(AiEvent.Delta("Hi"), AiEvent.Done), events)
            val (headers, body) = server.seen.getValue("/v1/chat")
            assertEquals("Bearer dev-token", headers["authorization"])
            assertEquals("Hello", JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("text"))
            try { service.voiceSession("marin", false); fail("401 must fail") } catch (e: AiException) { assertEquals(AiProblem.Unauthorized, e.problem) }
            assertFalse(HttpMyloAiService({ null }, true).configured)
        }
    }
}
