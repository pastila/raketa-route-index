package online.raketa.routeindex

/**
 * One route from `.idea/raketa-routes.json`, as written by the dumper.
 *
 * [path] uses `{name}` placeholders; a placeholder may carry its regex inline (`{id:\d+}`)
 * or in [requirements]. [declarationFile]:[declarationLine] is where the route is declared (`$group->post(...)`,
 * `->add(...)`, `#[Route]`, a yaml key); null for file-based apps and dumps made before it was collected.
 */
data class Route(
    val app: String,
    val hosts: List<String>,
    val methods: List<String>,
    val path: String,
    val requirements: Map<String, String>,
    val handler: String,
    val file: String?,
    val line: Int,
    val name: String?,
    val declarationFile: String? = null,
    val declarationLine: Int = 0,
)

data class RouteMatch(val route: CompiledRoute, val score: Int, val kind: Kind) {
    enum class Kind { URL, URL_SUFFIX, TEXT }
}

/**
 * A console command from `.idea/raketa-routes.json`. One record per command class: the same command
 * may be served by several [entries] (`core/bin/console` and `app/raketa/api/bin/console`, every
 * script in `api/profile-sync/cli`). [handler] and [file] are null when the class wasn't found (maker-bundle).
 */
data class ConsoleCommand(
    val name: String,
    val aliases: List<String>,
    val description: String,
    val hidden: Boolean,
    val handler: String?,
    val file: String?,
    val line: Int,
    val apps: List<String>,
    val entries: List<String>,
)

data class CommandMatch(val command: ConsoleCommand, val score: Int, val exact: Boolean)
