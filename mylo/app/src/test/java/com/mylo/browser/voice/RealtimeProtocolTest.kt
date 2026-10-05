package com.mylo.browser.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {
    @Test fun readsCaptionsInterruptionsAndToolCalls() {
        assertEquals(RealtimeEvent.SpeechStarted, Realtime.parse("""{"type":"input_audio_buffer.speech_started","audio_start_ms":120}"""))
        assertEquals(RealtimeEvent.Heard("item_1", "Where is the pricing?"),
            Realtime.parse("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":" Where is the pricing? "}"""))
        assertEquals(RealtimeEvent.SayingDelta("resp_1", "It's"), Realtime.parse("""{"type":"response.output_audio_transcript.delta","response_id":"resp_1","delta":"It's"}"""))
        assertEquals("beta names still work", RealtimeEvent.SayingDelta("resp_1", "It's"), Realtime.parse("""{"type":"response.audio_transcript.delta","response_id":"resp_1","delta":"It's"}"""))
        val call = Realtime.parse("""{"type":"response.function_call_arguments.done","call_id":"call_9","name":"scroll_to","arguments":"{\"target\":\"Pricing\"}"}""") as RealtimeEvent.ToolCall
        assertEquals("call_9", call.callId)
        assertEquals("Pricing", call.arguments.getString("target"))
        assertEquals(RealtimeEvent.Failure("rate_limit", "Slow down"), Realtime.parse("""{"type":"error","error":{"code":"rate_limit","message":"Slow down"}}"""))
        assertNull(Realtime.parse("""{"type":"rate_limits.updated"}"""))
        assertNull(Realtime.parse("not json"))
    }

    @Test fun turnTakingAndStopping() {
        val handsFree = JSONObject(Realtime.turnTaking(handsFree = true)).getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertEquals("semantic_vad", handsFree.getJSONObject("turn_detection").getString("type"))
        assertTrue(handsFree.getJSONObject("turn_detection").getBoolean("interrupt_response"))
        val hold = JSONObject(Realtime.turnTaking(handsFree = false)).getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertTrue("press and hold turns the service's turn detection off", hold.isNull("turn_detection"))
        assertEquals(listOf("input_audio_buffer.commit", "response.create"), Realtime.holdEnded().map { JSONObject(it).getString("type") })
        assertEquals(listOf("response.cancel", "output_audio_buffer.clear"), Realtime.stopSpeaking().map { JSONObject(it).getString("type") })
        val result = JSONObject(Realtime.toolResult("call_9", JSONObject().put("done", true))[0]).getJSONObject("item")
        assertEquals("function_call_output", result.getString("type"))
        assertEquals("{\"done\":true}", result.getString("output"))
    }
}
