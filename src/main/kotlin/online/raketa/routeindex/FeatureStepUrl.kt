package online.raketa.routeindex

/**
 * Finds the URL under the caret in a behat `.feature` file and the app that serves it.
 *
 * - `клиент делает [multipart/form-data ]POST запрос на /v1/...` — the app is named by the next
 *   `<App> АПИ возвращает http-статус N` step of the scenario, else guessed from the file path;
 * - `АПИ Авиа на curl-запрос GET /admin/...` — a stub of another Raketa service, the app comes from
 *   the service name; stubs of external suppliers (`Поставщик АПИ ...`, `avia.supplier.ru`) are skipped;
 * - `Функциональность: GET /api/...` — the app comes from the first response step of the file.
 */
object FeatureStepUrl {
    data class Hit(val method: String, val url: String, val start: Int, val end: Int, val app: String?)

    private val REQUEST = Regex("""клиент делает (?:\S+ )?(GET|POST|PUT|PATCH|DELETE|OPTIONS) запрос на (\S+)""")
    private val STUB = Regex("""^\s*\S+\s+(.+?) на curl-запрос (GET|POST|PUT|PATCH|DELETE|HEAD) (\S+)""")
    private val FEATURE = Regex("""^\s*(?:Функциональность|Функция|Feature):\s*(GET|POST|PUT|PATCH|DELETE) (\S+)""")
    private val RESPONSE = Regex("""^\s*\S+\s+(.+?) АПИ возвращает http-статус""", RegexOption.IGNORE_CASE)
    private val SCENARIO = Regex("""^\s*(Сценарий|Структура сценария|Предыстория|Scenario|Scenario Outline|Background)\b""")

    /**
     * Whole-segment placeholders match any route segment: behat `~lastServiceId~`, Scenario Outline
     * `<owner_type>`, `{profileId}` in feature titles.
     */
    private val PLACEHOLDER = Regex("""(?<=/)(?:~\w+~|<\w+>|\{\w+})(?=/|$|\?)""")

    /** Names in `<App> АПИ возвращает http-статус`, normalized (see [normalize]). */
    private val RESPONSE_APPS = mapOf(
        "raketa" to "raketa",
        "авиа" to "avia",
        "mobile" to "mobile",
        "agency" to "agency",
        "client" to "client",
        "hotel" to "hotels",
        "rail" to "rail",
        "трансфер" to "transfer",
        "contract" to "contract",
        "aeroexpress" to "aeroexpress",
        "profile sync" to "profile-sync",
        "bus" to "bus",
        "автобусы" to "bus",
        "profile" to "profile",
        "oauth" to "oauth",
        "queue" to "queue",
        "static" to "static",
    )

    /** Stub service names — core/src/Behat/TestGenerator/HostToServiceNameMapFactory.php plus aliases in use. */
    private val STUB_APPS = mapOf(
        "авиа" to "avia",
        "жд" to "rail",
        "oauth" to "oauth",
        "контрактов" to "contract",
        "contract" to "contract",
        "трансфер" to "transfer",
        "профайл синк" to "profile-sync",
        "queue" to "queue",
        "отелей" to "hotels",
        "аероэкспресс" to "aeroexpress",
        "аэроэкспресс" to "aeroexpress",
        "автобусы" to "bus",
        "profile" to "profile",
    )

    private val API_DIR = Regex("""(?:^|/)api/([\w-]+)/""")

    fun normalize(name: String): String = name.lowercase()
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotEmpty() && it != "api" && it != "апи" }
        .joinToString(" ")

    /** URL with behat placeholders replaced, ready for [RouteQuery.parse]. */
    fun queryUrl(url: String): String = PLACEHOLDER.replace(url, RouteQuery.ANY_SEGMENT)

    /**
     * @param lines the whole file split into lines
     * @param lineIndex line under the caret
     * @param column caret column in that line
     * @param filePath project-relative path, used when no response step names the app
     */
    fun find(lines: List<String>, lineIndex: Int, column: Int, filePath: String): Hit? {
        val line = lines.getOrNull(lineIndex) ?: return null

        STUB.find(line)?.let { m ->
            val url = m.groups[3]!!
            if (column !in url.range.first..url.range.last + 1) return null
            val app = STUB_APPS[normalize(m.groupValues[1])] ?: return null
            return Hit(m.groupValues[2], url.value, url.range.first, url.range.last + 1, app)
        }

        REQUEST.find(line)?.let { m ->
            val url = m.groups[2]!!
            if (column !in url.range.first..url.range.last + 1) return null
            val app = fixStatic(responseApp(lines, lineIndex + 1, stopAtScenario = true), filePath)
            return Hit(m.groupValues[1], url.value, url.range.first, url.range.last + 1, app)
        }

        FEATURE.find(line)?.let { m ->
            val url = m.groups[2]!!
            if (column !in url.range.first..url.range.last + 1) return null
            val app = fixStatic(responseApp(lines, lineIndex + 1, stopAtScenario = false), filePath)
            return Hit(m.groupValues[1], url.value, url.range.first, url.range.last + 1, app)
        }
        return null
    }

    private fun responseApp(lines: List<String>, from: Int, stopAtScenario: Boolean): String? {
        for (i in from until lines.size) {
            if (stopAtScenario && SCENARIO.containsMatchIn(lines[i])) return null
            RESPONSE.find(lines[i])?.let { m ->
                val name = normalize(m.groupValues[1])
                return RESPONSE_APPS[name] ?: RESPONSE_APPS.entries.firstOrNull { name.contains(it.key) }?.value
            }
        }
        return null
    }

    /** `Static АПИ` in core means app/static scripts, in api/static — the static service. */
    private fun fixStatic(app: String?, filePath: String): String {
        val byPath = pathApp(filePath)
        return if (app == null || (app == "static" && byPath == "static-api")) byPath else app
    }

    /** api/<service>/features → that service; everything in core runs against raketa. */
    fun pathApp(filePath: String): String {
        val dir = API_DIR.find(filePath.replace('\\', '/'))?.groupValues?.get(1) ?: return "raketa"
        return if (dir == "static") "static-api" else dir
    }
}
