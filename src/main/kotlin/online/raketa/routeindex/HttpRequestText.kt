package online.raketa.routeindex

/**
 * A route as a request of the `.http` format, which both the HTTP Client of PhpStorm and the one of Raketa read.
 * Placeholders become variables (`{serviceId}` → `{{serviceId}}`) for the user to fill in.
 */
object HttpRequestText {
    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    /** The method to send: the first one of the route other than `HEAD`, `GET` for a route of any method. */
    fun method(route: Route): String =
        route.methods.map { it.uppercase() }.let { methods -> methods.firstOrNull { it != "HEAD" } ?: methods.firstOrNull() } ?: "GET"

    /** `### POST /v3/chat/service/{serviceId}` — the title line that tells whether a request is already in the file. */
    fun title(route: Route): String = "### ${method(route)} ${RouteTarget.displayPath(route.path)}"

    fun request(route: Route): String = buildString {
        val method = method(route)
        val path = RouteTarget.displayPath(route.path).replace(Regex("""\{([^{}]+)}"""), "{{$1}}")
        val host = route.hosts.firstOrNull { !it.contains('*') } ?: "{{host}}"
        append(title(route)).append('\n')
        append("# ").append(route.app).append(": ").append(route.handler).append('\n')
        append(method).append(" https://").append(host).append(path).append('\n')
        if (method in BODY_METHODS) append("Content-Type: application/json\n\n{}\n")
    }

    /** The offset of the title line of [route] in [text], or -1. */
    fun offset(text: String, route: Route): Int {
        val title = title(route)
        if (text.startsWith("$title\n")) return 0
        val index = text.indexOf("\n$title\n")
        return if (index < 0) -1 else index + 1
    }
}
