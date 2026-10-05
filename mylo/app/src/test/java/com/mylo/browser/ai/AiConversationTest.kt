package com.mylo.browser.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
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
    @Test fun streamsFromTheServiceWithTheMyloToken() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        var body = ""
        server.createContext("/v1/chat") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            body = exchange.requestBody.readBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("event: delta\ndata: {\"text\":\"Hi\"}\n\nevent: done\ndata: {}\n\n".toByteArray()) }
        }
        server.createContext("/v1/voice/sessions") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val service = HttpMyloAiService({ AiEndpoint("http://127.0.0.1:${server.address.port}", "dev-token") }, allowDevCleartext = true)
            assertTrue(service.configured)
            val events = service.chat("c1", listOf(AiTurn(AiTurn.Role.User, "Hello")), AiContext(), private = false).toList()
            assertEquals(listOf(AiEvent.Delta("Hi"), AiEvent.Done), events)
            assertEquals("Bearer dev-token", authorization)
            assertEquals("Hello", JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("text"))
            try { service.voiceSession("marin", false); fail("401 must fail") } catch (e: AiException) { assertEquals(AiProblem.Unauthorized, e.problem) }
            assertFalse(HttpMyloAiService({ null }, true).configured)
        } finally {
            server.stop(0)
        }
    }
}
