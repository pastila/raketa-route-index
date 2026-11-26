package online.raketa.routeindex

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RouteMatcherTest {
    private fun route(
        path: String,
        handler: String,
        app: String = "raketa",
        methods: List<String> = listOf("GET"),
        requirements: Map<String, String> = emptyMap(),
        hosts: List<String> = listOf("raketa.in"),
    ) = CompiledRoute(Route(app, hosts, methods, path, requirements, handler, "x.php", 1, null))

    private val routes = listOf(
        route("/api/profiles/{profileId}/remarks", "RemarkController::get", requirements = mapOf("profileId" to "\\d+")),
        route("/api/profiles/{profileId}/remarks", "RemarkController::post", methods = listOf("POST"), requirements = mapOf("profileId" to "\\d+")),
        route("/api/profiles/travellers", "TravellersController::search"),
        route("/v1/profile/{profileId}/remarks", "RemarksGetOperation::handle", app = "profile", hosts = listOf("profile.raketa.in")),
        route("/v2/services/{serviceId:\\d+}", "ServiceV2Controller::getById", app = "agency", hosts = listOf("agency.raketa.in")),
        route("/{path}", "IndexController::index", requirements = mapOf("path" to "(?!api/).*")),
        route("/directory/client/", "app/fcc/public/directory/client/index.php", app = "fcc", methods = emptyList(), hosts = listOf("fcc.raketa.in")),
        route("/static/photo/ac/{rest:.+}", "photo/acase.php", app = "static"),
        route("/api/{entityType}/{entityId}/logs/{child}", "GetLogsController::get", requirements = mapOf("entityType" to "\\w+", "entityId" to "\\d+", "child" to "(budget|lso|clientContracts)?")),
    )

    private fun search(q: String): List<RouteMatch> {
        val query = RouteQuery.parse(q)
        return routes.mapNotNull { RouteMatcher.match(it, query) }.sortedByDescending { it.score }
    }

    private fun best(q: String): String? = search(q).firstOrNull()?.route?.route?.handler

    @Test
    fun `full URL with host and numeric id`() {
        assertEquals("RemarkController::get", best("https://raketa.in/api/profiles/645701/remarks"))
    }

    @Test
    fun `path without leading slash and without api prefix`() {
        assertEquals("RemarkController::get", best("api/profiles/645701/remarks"))
        val tail = search("profiles/645701/remarks").map { it.route.route.handler }
        assertTrue(tail.containsAll(listOf("RemarkController::get", "RemarkController::post")))
    }

    @Test
    fun `method prefix picks the right action`() {
        assertEquals("RemarkController::post", best("POST /api/profiles/645701/remarks"))
    }

    @Test
    fun `requirements reject non matching values`() {
        assertTrue(search("/api/profiles/abc/remarks").none { it.route.route.handler.startsWith("RemarkController") })
        assertEquals("TravellersController::search", best("/api/profiles/travellers"))
    }

    @Test
    fun `query placeholders match route parameters with different names and requirements`() {
        val full = search("GET /api/agency/{clientId}/logs/clientContracts").first()
        assertEquals("GetLogsController::get", full.route.route.handler)
        assertEquals(RouteMatch.Kind.URL, full.kind)
        val suffix = search("agency/{clientId}/logs/clientContracts").first()
        assertEquals("GetLogsController::get", suffix.route.route.handler)
        assertEquals(RouteMatch.Kind.URL_SUFFIX, suffix.kind)
        assertEquals("ServiceV2Controller::getById", best("/v2/services/{id}"))
        assertTrue(search("/api/agency/abc/logs/clientContracts").none { it.route.route.handler == "GetLogsController::get" })
        assertTrue(search("/api/agency/{clientId}/logs/unknown").none { it.route.route.handler == "GetLogsController::get" })
        assertTrue(search("/api/agency/{clientId}/{section}/clientContracts").none { it.route.route.handler == "GetLogsController::get" })
    }

    @Test
    fun `host decides between apps with similar paths`() {
        assertEquals("RemarksGetOperation::handle", best("https://profile.raketa.in/v1/profile/5/remarks"))
        assertEquals("ServiceV2Controller::getById", best("https://agency.stage.example.com/v2/services/42?x=1"))
        assertTrue(search("https://agency.raketa.in/v2/services/abc").none { it.route.route.app == "agency" })
    }

    @Test
    fun `legacy index php and language prefix`() {
        assertEquals("app/fcc/public/directory/client/index.php", best("https://fcc.raketa.in/ru/directory/client/index.php"))
        assertEquals("app/fcc/public/directory/client/index.php", best("fcc.raketa.in/directory/client/"))
    }

    @Test
    fun `catch-all tails`() {
        assertEquals("photo/acase.php", best("/static/photo/ac/1/2/3.jpg"))
        assertEquals("IndexController::index", best("https://raketa.in/ru/orders"))
        // The SPA catch-all excludes api/
        assertNull(search("/api/unknown/thing").firstOrNull { it.route.route.handler == "IndexController::index" })
    }

    @Test
    fun `last placeholder accepting empty string is optional`() {
        assertEquals("GetLogsController::get", best("/api/employee/645701/logs/"))
        assertEquals("GetLogsController::get", best("/api/employee/645701/logs/budget"))
        assertTrue(search("/api/employee/645701/logs/other").none { it.route.route.handler == "GetLogsController::get" })
    }

    @Test
    fun `search completes the last literal segment after validated route parameters`() {
        val logs = routes.single { it.route.handler == "GetLogsController::get" }
        val query = RouteQuery.parse("GET https://raketa.in/api/agency/2/log")
        val exact = route("/api/agency/2/log", "LogController::get")

        val match = search("GET https://raketa.in/api/agency/2/log").first()
        assertEquals(logs, match.route)
        assertEquals(RouteMatch.Kind.URL_PREFIX, match.kind)
        assertTrue(RouteMatcher.match(exact, query)!!.score > match.score)
        assertNull(RouteMatcher.match(logs, RouteQuery.parse("/api/agency/abc/log")))
        assertTrue(RouteIndex.resolveIn(routes, "raketa", "GET", "/api/agency/2/log").isEmpty())
    }

    @Test
    fun `plain text searches path and handler`() {
        assertEquals(RouteMatch.Kind.TEXT, search("remarkcontroller").first().kind)
        assertEquals(2, search("RemarkController").size)
        assertTrue(search("remarks").size >= 3)
    }

    /** Smoke test against a real dump: `-Draketa.routes=/path/to/raketa-routes.json` */
    @Test
    fun `real dump resolves known urls`() {
        val path = System.getProperty("raketa.routes") ?: System.getenv("RAKETA_ROUTES")
        assumeTrue(path != null && File(path).isFile)
        val root = File(path!!).reader().use { JsonParser.parseReader(it).asJsonObject }
        val all = root.getAsJsonArray("routes").map { CompiledRoute(RouteIndex.parseRoute(it.asJsonObject)) }
        fun assertTop(q: String, handlerSuffix: String) {
            val query = RouteQuery.parse(q)
            val found = all.mapNotNull { RouteMatcher.match(it, query) }.sortedByDescending { it.score }.take(5)
            val dump = found.joinToString("\n") { "${it.score} ${it.route.route.app} ${it.route.route.path} ${it.route.route.handler}" }
            assertTrue("$q →\n$dump", found.firstOrNull()?.route?.route?.handler?.endsWith(handlerSuffix) == true)
        }
        assertTop("https://raketa.in/api/profiles/645701/remarks", "RemarkController::get")
        assertTop("POST api/profiles/645701/remarks", "RemarkController::post")
        assertTop("GET /api/agency/{clientId}/logs/clientContracts", "GetLogsController::get")
        assertTop("GET /api/agency/3156/logs/clientContracts", "GetLogsController::get")
        assertTop("/api/agency/2/log", "GetLogsController::get")
        assertTop("https://avia.raketa.in/v1/schedule/12", "GetFlightScheduleByContractController::__invoke")
        assertTop("https://profile.raketa.in/v1/profile/5/remarks", "RemarksGetOperation::handle")
        assertTop("https://fcc.raketa.in/ru/directory/api-profile-sync/profile-remarks/sabre/edit.php?id=1", "sabre/edit.php")
    }
}
