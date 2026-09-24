package online.raketa.routeindex

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FeatureStepUrlTest {
    private val feature = """
        Функциональность: GET /api/profiles/{profileId}/remarks

          Сценарий: получение ремарок
            Дано АПИ Авиа на curl-запрос GET /admin/settings/aircompany с данными из файла empty.txt отвечает a.json
            И avia.supplier.ru на curl-запрос POST /v1/Accounts/login с данными из файла Auth-request.xml отвечает b.xml
            Когда клиент делает GET запрос на /api/profiles/~lastProfileId~/remarks
            Тогда Raketa АПИ возвращает http-статус 200

          Сценарий: мобилка
            Когда клиент делает multipart/form-data POST запрос на /v3/chat/message с данными json
            Тогда Mobile АПИ возвращает http-статус 200

          Сценарий: без шага ответа
            Когда клиент делает GET запрос на /v1/service/1
    """.trimIndent().lines()

    private fun at(lineIndex: Int, text: String, path: String = "core/src/Features/x/feature.feature"): FeatureStepUrl.Hit? {
        val column = feature[lineIndex].indexOf(text) + 1
        return FeatureStepUrl.find(feature, lineIndex, column, path)
    }

    @Test
    fun `request step takes the app from the response step`() {
        val hit = at(5, "/api/profiles")!!
        assertEquals("GET", hit.method)
        assertEquals("/api/profiles/~lastProfileId~/remarks", hit.url)
        assertEquals("raketa", hit.app)
        assertEquals("/api/profiles/\u0000/remarks", FeatureStepUrl.queryUrl(hit.url))
        assertEquals("/api/balance/\u0000/\u0000/x?a=<b>", FeatureStepUrl.queryUrl("/api/balance/<owner_type>/<owner_id>/x?a=<b>"))
        assertEquals("mobile", at(9, "/v3/chat")!!.app)
    }

    @Test
    fun `without a response step the app comes from the path`() {
        assertEquals("raketa", at(13, "/v1/service")!!.app)
        assertEquals("avia", at(13, "/v1/service", "api/avia/features/Service/Get/feature.feature")!!.app)
        assertEquals("static-api", FeatureStepUrl.pathApp("api/static/features/a.feature"))
    }

    @Test
    fun `stubs of raketa services resolve, external suppliers do not`() {
        val stub = at(3, "/admin/settings")!!
        assertEquals("avia", stub.app)
        assertEquals("/admin/settings/aircompany", stub.url)
        assertNull(at(4, "/v1/Accounts"))
    }

    @Test
    fun `only the url itself is a link`() {
        assertNull(at(5, "клиент делает"))
        assertEquals("raketa", at(0, "/api/profiles")!!.app)
    }

    @Test
    fun `response app names are normalized`() {
        assertEquals("hotels", FeatureStepUrl.find(listOf("И клиент делает GET запрос на /v2/x", "Тогда Api hotel АПИ возвращает http-статус 200"), 0, 31, "a")!!.app)
        assertEquals("profile-sync", FeatureStepUrl.find(listOf("И клиент делает GET запрос на /v2/x", "Тогда API Profile Sync АПИ возвращает http-статус 200"), 0, 31, "a")!!.app)
        assertEquals("bus", FeatureStepUrl.find(listOf("И клиент делает GET запрос на /v2/x", "Тогда api_bus АПИ возвращает http-статус 200"), 0, 31, "a")!!.app)
    }

    /**
     * Every URL of every feature in the repo against a real dump:
     * `./gradlew test -PraketaRoutes=../raketa/.idea/raketa-routes.json`. Prints unresolved ones.
     */
    @Test
    fun `real features resolve`() {
        val path = System.getProperty("raketa.routes")
        assumeTrue(path != null && File(path).isFile)
        val repo = File(path!!).parentFile.parentFile
        val root = File(path).reader().use { JsonParser.parseReader(it).asJsonObject }
        val routes = root.getAsJsonArray("routes").map { CompiledRoute(RouteIndex.parseRoute(it.asJsonObject)) }

        val stats = sortedMapOf<String, IntArray>()
        val misses = mutableListOf<String>()
        val features = sequenceOf("core/src", "api").map { File(repo, it) }
            .flatMap { dir -> dir.walkTopDown().onEnter { it.name != "vendor" && it.name != "Generated" } }
            .filter { it.isFile && it.extension == "feature" }
        for (file in features) {
            val rel = file.relativeTo(repo).path
            val lines = file.readLines()
            for ((i, line) in lines.withIndex()) {
                val idx = line.indexOf(" /")
                if (idx < 0) continue
                val hit = FeatureStepUrl.find(lines, i, idx + 2, rel) ?: continue
                val found = RouteIndex.resolveIn(routes, hit.app, hit.method, FeatureStepUrl.queryUrl(hit.url))
                val s = stats.getOrPut(hit.app ?: "?") { IntArray(2) }
                s[0]++
                if (found.isNotEmpty()) s[1]++ else misses += "${hit.app} ${hit.method} ${hit.url}"
            }
        }
        println(stats.entries.joinToString("\n") { "${it.key}: ${it.value[1]}/${it.value[0]}" })
        println("unresolved (distinct, top per app):\n" + misses.groupingBy { it }.eachCount().entries
            .groupBy { it.key.substringBefore(' ') }.values
            .flatMap { e -> e.sortedByDescending { it.value }.take(8) }
            .joinToString("\n") { "${it.value} ${it.key}" })
        val total = stats.values.sumOf { it[0] }
        val ok = stats.values.sumOf { it[1] }
        assertTrue("resolved $ok of $total", ok > total * 0.9)
    }
}
