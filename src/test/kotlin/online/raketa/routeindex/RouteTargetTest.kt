package online.raketa.routeindex

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RouteTargetTest {
    private fun route(app: String, hosts: List<String>, method: String, path: String, handler: String, file: String) =
        Route(app, hosts, listOf(method), path, emptyMap(), handler, file, 10, null)

    private val chatFile = "api/mobile/src/Presentation/Operation/Chat/PostMessageForServiceHandler.php"
    private val chat = route("mobile", listOf("mobile.raketa.in"), "POST", "/v3/chat/service/{serviceId:[0-9]+}",
        "Mobile\\Presentation\\Operation\\Chat\\PostMessageForServiceHandler::__invoke", chatFile)
    private val remarksFile = "app/raketa/api/src/Controller/RemarkController.php"
    private val remarksGet = route("raketa", listOf("raketa.in", "master.*"), "GET", "/api/profiles/{profileId}/remarks", "Controller\\RemarkController::get", remarksFile)
    private val remarksAdd = route("raketa", listOf("raketa.in", "master.*"), "POST", "/api/profiles/{profileId}/remarks", "Controller\\RemarkController::add", remarksFile)
    private val routes = listOf(chat, remarksGet, remarksAdd)

    @Test
    fun `routes of the method in that file`() {
        assertEquals(listOf(chat), RouteTarget.forMethod(routes, chatFile, "__invoke"))
        assertEquals(listOf(remarksAdd), RouteTarget.forMethod(routes, remarksFile, "Add"))
        assertTrue(RouteTarget.forMethod(routes, remarksFile, "__construct").isEmpty())
        assertTrue(RouteTarget.forMethod(routes, "api/mobile/src/Other.php", "__invoke").isEmpty())
    }

    @Test
    fun `routes of an inherited method go to the class`() {
        val file = "api/contract/v1/src/Operation/GetDataFieldsOperation.php"
        val fields = route("contract", listOf("contract.raketa.in"), "GET", "/v1.0/dictionary/suppliers/{serviceType-supplier}/data-fields",
            "Api\\Contract\\V1\\Operation\\GetDataFieldsOperation::__invoke", file)
        val inherited = "final readonly class GetDataFieldsOperation extends BaseOperation {\n    public function handle() {}\n}"
        assertEquals(listOf(fields), RouteTarget.forClass(listOf(fields), file, "GetDataFieldsOperation", inherited))
        assertTrue(RouteTarget.forMethod(listOf(fields), file, "handle").isEmpty())
        val own = "class GetDataFieldsOperation {\n    public function __invoke() {}\n}"
        assertTrue(RouteTarget.forClass(listOf(fields), file, "GetDataFieldsOperation", own).isEmpty())
        assertTrue(RouteTarget.forClass(listOf(fields), file, "Other", inherited).isEmpty())
    }

    @Test
    fun `url and path drop inline requirements`() {
        assertEquals("https://mobile.raketa.in/v3/chat/service/{serviceId}", RouteTarget.url(chat))
        assertEquals("/v1/{code}/x.{_format}", RouteTarget.displayPath("/v1/{code:\\d{3}}/x.{_format}"))
        assertEquals("https://raketa.in/api/profiles/{profileId}/remarks", RouteTarget.url(remarksGet))
        assertEquals("/a", RouteTarget.url(chat.copy(hosts = listOf("master.*"), path = "/a")))
        assertEquals("POST /v3/chat/service/{serviceId}", RouteTarget.title(chat))
    }

    @Test
    fun `feature steps calling the route`() {
        val feature = """
            Функциональность: чат
              Сценарий: сообщение
                Когда клиент делает POST запрос на /v3/chat/service/~lastServiceId~ с данными json
                Тогда Mobile АПИ возвращает http-статус 200
              Сценарий: ремарки
                Когда клиент делает GET запрос на /api/profiles/1/remarks
                Тогда Raketa АПИ возвращает http-статус 200
                Когда клиент делает POST запрос на /api/profiles/1/remarks
                Тогда Raketa АПИ возвращает http-статус 200
        """.trimIndent()
        val compiled = routes.map { CompiledRoute(it) }
        val chatUsages = FeatureUsages.inFile(compiled, listOf(chat), "api/mobile/features/a.feature", feature)
        assertEquals(listOf(3), chatUsages.map { it.line })
        assertTrue(chatUsages.single().text.startsWith("Когда клиент делает POST"))
        assertEquals(listOf(8), FeatureUsages.inFile(compiled, listOf(remarksAdd), "core/src/Features/a.feature", feature).map { it.line })
        assertTrue(FeatureUsages.inFile(compiled, listOf(chat), "a.feature", "Когда клиент делает GET запрос на /v2/other").isEmpty())
    }

    /** Every handler of a real dump finds its routes: `./gradlew test -PraketaRoutes=../raketa/.idea/raketa-routes.json` */
    @Test
    fun `real dump gives every controller method its routes`() {
        val path = System.getProperty("raketa.routes")
        assumeTrue(path != null && File(path).isFile)
        val root = File(path!!).reader().use { JsonParser.parseReader(it).asJsonObject }
        val all = root.getAsJsonArray("routes").map { RouteIndex.parseRoute(it.asJsonObject) }
        val methods = all.filter { it.file != null && it.handler.contains("::") }
        assertTrue(methods.all { it in RouteTarget.forMethod(all, it.file!!, it.handler.substringAfter("::")) })
        val chatRoutes = RouteTarget.forMethod(all, chatFile, "__invoke")
        assertEquals(listOf("https://mobile.raketa.in/v3/chat/service/{serviceId}"), chatRoutes.map { RouteTarget.url(it) })
        val declared = methods.count { it.declarationFile != null }
        assumeTrue("dump without declarations", declared > 0)
        assertTrue("declared $declared of ${methods.size}", declared > methods.size * 0.99)
        assertEquals("api/mobile/src/RoutingBuilder.php", chatRoutes.single().declarationFile)
        val repo = File(path).parentFile.parentFile
        val line = File(repo, chatRoutes.single().declarationFile!!).readLines()[chatRoutes.single().declarationLine - 1]
        assertTrue(line, line.contains("'/chat/service/{serviceId:[0-9]+}'") && line.contains("PostMessageForServiceHandler::class"))
    }

    @Test
    fun `declaration is read from the dump`() {
        val json = """{"app":"mobile","hosts":[],"methods":["POST"],"path":"/x","handler":"A::b","file":"a.php","line":3,"name":null,"declaration":{"file":"r.php","line":7}}"""
        val route = RouteIndex.parseRoute(JsonParser.parseString(json).asJsonObject)
        assertEquals("r.php" to 7, route.declarationFile to route.declarationLine)
        val old = RouteIndex.parseRoute(JsonParser.parseString(json.replace(Regex(",\"declaration\".*}}"), "}")).asJsonObject)
        assertEquals(null to 0, old.declarationFile to old.declarationLine)
    }
}
