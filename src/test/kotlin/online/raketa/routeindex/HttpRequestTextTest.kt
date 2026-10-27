package online.raketa.routeindex

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRequestTextTest {
    private val chat = Route("mobile", listOf("mobile.raketa.in"), listOf("POST"), "/v3/chat/service/{serviceId:[0-9]+}", emptyMap(),
        "Mobile\\Chat\\PostMessageForServiceHandler::__invoke", "api/mobile/src/Chat/PostMessageForServiceHandler.php", 10, null)
    private val remarks = Route("raketa", listOf("master.*", "raketa.in"), listOf("HEAD", "GET"), "/api/profiles/{profileId}/remarks", emptyMap(),
        "Controller\\RemarkController::get", "app/raketa/api/src/Controller/RemarkController.php", 10, null)

    @Test
    fun `request with variables and a json body`() {
        assertEquals(
            """
            ### POST /v3/chat/service/{serviceId}
            # mobile: Mobile\Chat\PostMessageForServiceHandler::__invoke
            POST https://mobile.raketa.in/v3/chat/service/{{serviceId}}
            Content-Type: application/json

            {}

            """.trimIndent(),
            HttpRequestText.request(chat),
        )
        assertEquals("GET https://raketa.in/api/profiles/{{profileId}}/remarks", HttpRequestText.request(remarks).lines()[2])
    }

    @Test
    fun `method and host fallbacks`() {
        assertEquals("GET", HttpRequestText.method(remarks.copy(methods = emptyList())))
        assertEquals("HEAD", HttpRequestText.method(remarks.copy(methods = listOf("HEAD"))))
        assertEquals("GET https://{{host}}/a", HttpRequestText.request(remarks.copy(hosts = listOf("master.*"), path = "/a")).lines()[2])
    }

    @Test
    fun `request already in the file`() {
        val text = HttpRequestText.request(remarks) + "\n" + HttpRequestText.request(chat)
        assertEquals(0, HttpRequestText.offset(text, remarks))
        assertEquals(text.indexOf("### POST"), HttpRequestText.offset(text, chat))
        assertEquals(-1, HttpRequestText.offset(text, chat.copy(path = "/other")))
    }
}
