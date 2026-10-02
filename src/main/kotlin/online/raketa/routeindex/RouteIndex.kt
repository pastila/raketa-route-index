package online.raketa.routeindex

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * Routes of every app in the repo, read from `.idea/raketa-routes.json` (the `.idea` dir is
 * gitignored in raketa, so the dump never reaches the git index). Reloaded when the file changes.
 */
@Service(Service.Level.PROJECT)
class RouteIndex(private val project: Project) {
    @Volatile
    private var cached: Snapshot? = null

    class Snapshot(val modified: Long, val routes: List<CompiledRoute>, val commands: List<ConsoleCommand>, val generatedAt: String?)

    val indexFile: Path? get() = project.basePath?.let { Path.of(it, ".idea", FILE_NAME) }

    fun snapshot(): Snapshot? {
        val file = indexFile ?: return null
        if (!Files.isRegularFile(file)) return null
        val modified = Files.getLastModifiedTime(file).toMillis()
        cached?.takeIf { it.modified == modified }?.let { return it }
        return try {
            load(file, modified).also { cached = it }
        } catch (e: Exception) {
            LOG.warn("Cannot read $file", e)
            null
        }
    }

    fun search(pattern: String, limit: Int = 200): List<RouteMatch> {
        val routes = snapshot()?.routes ?: return emptyList()
        val query = RouteQuery.parse(pattern)
        return routes.asSequence()
            .mapNotNull { RouteMatcher.match(it, query) }
            .sortedWith(compareByDescending<RouteMatch> { it.score }.thenBy { it.route.route.path })
            .take(limit)
            .toList()
    }

    fun searchCommands(pattern: String, limit: Int = 100): List<CommandMatch> {
        val commands = snapshot()?.commands ?: return emptyList()
        return commands.asSequence()
            .mapNotNull { CommandMatcher.match(it, pattern) }
            .sortedWith(compareByDescending<CommandMatch> { it.score }.thenBy { it.command.name })
            .take(limit)
            .toList()
    }

    /** Commands named exactly [name] (or by an alias), e.g. from a behat `команда "..."` step. */
    fun resolveCommand(name: String): List<ConsoleCommand> =
        snapshot()?.commands?.filter { it.name == name || name in it.aliases } ?: emptyList()

    /**
     * Routes of [app] serving `METHOD url`: the best-scored ones (GET and POST on one path both come
     * back when the method is unknown). Full-path matches win over tail matches.
     */
    fun resolve(app: String?, method: String?, url: String): List<Route> =
        resolveIn(snapshot()?.routes ?: emptyList(), app, method, url)

    private fun load(file: Path, modified: Long): Snapshot {
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        val routes = root.getAsJsonArray("routes").map { parseRoute(it.asJsonObject) }
        // Dumps before version 2 have no commands.
        val commands = root.getAsJsonArray("commands")?.map { parseCommand(it.asJsonObject) } ?: emptyList()
        return Snapshot(modified, routes.map { CompiledRoute(it) }, commands, root.str("generatedAt"))
    }

    companion object {
        const val FILE_NAME = "raketa-routes.json"
        private val LOG = logger<RouteIndex>()

        fun getInstance(project: Project): RouteIndex = project.getService(RouteIndex::class.java)

        fun resolveIn(routes: List<CompiledRoute>, app: String?, method: String?, url: String): List<Route> {
            val query = RouteQuery.parse(listOfNotNull(method, url).joinToString(" "))
            val matches = routes.asSequence()
                .filter { app == null || it.route.app == app }
                .mapNotNull { RouteMatcher.match(it, query) }
                .filter { it.kind != RouteMatch.Kind.TEXT }
                .toList()
            val best = matches.maxOfOrNull { it.score } ?: return emptyList()
            return matches.filter { it.score == best }.map { it.route.route }
        }

        fun parseRoute(o: JsonObject): Route = Route(
            app = o.str("app") ?: "",
            hosts = o.strList("hosts"),
            methods = o.strList("methods"),
            path = o.str("path") ?: "/",
            requirements = o.get("requirements")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.entrySet()
                ?.associate { it.key to it.value.asString }
                ?: emptyMap(),
            handler = o.str("handler") ?: "",
            file = o.str("file"),
            line = o.get("line")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            name = o.str("name"),
        )

        fun parseCommand(o: JsonObject): ConsoleCommand = ConsoleCommand(
            name = o.str("name") ?: "",
            aliases = o.strList("aliases"),
            description = o.str("description") ?: "",
            hidden = o.get("hidden")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            handler = o.str("handler"),
            file = o.str("file"),
            line = o.get("line")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            apps = o.strList("apps"),
            entries = o.strList("entries"),
        )

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString

        private fun JsonObject.strList(key: String): List<String> =
            getAsJsonArray(key)?.map { it.asString } ?: emptyList()
    }
}
