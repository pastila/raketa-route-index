package online.raketa.routeindex

/**
 * One route from `.idea/raketa-routes.json`, as written by the dumper.
 *
 * [path] uses `{name}` placeholders; a placeholder may carry its regex inline (`{id:\d+}`)
 * or in [requirements].
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
)

data class RouteMatch(val route: CompiledRoute, val score: Int, val kind: Kind) {
    enum class Kind { URL, URL_SUFFIX, TEXT }
}
