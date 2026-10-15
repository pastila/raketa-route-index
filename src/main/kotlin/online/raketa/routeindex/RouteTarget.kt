package online.raketa.routeindex

/** The routes served by one controller method, and how to spell them for the clipboard. */
object RouteTarget {
    /**
     * Routes whose handler is the method [methodName] declared in [file]. Matching by the method name rather
     * than the dumped line keeps the gutter icon in place while the file is being edited. PHP method names
     * are case-insensitive.
     */
    fun forMethod(routes: List<Route>, file: String, methodName: String): List<Route> =
        routes.filter { it.file == file && it.handler.substringAfter("::", "").equals(methodName, ignoreCase = true) }
            .sortedWith(compareBy({ it.path }, { it.methods.joinToString() }))

    /** `/v3/chat/service/{serviceId:[0-9]+}` → `/v3/chat/service/{serviceId}`: inline requirements dropped. */
    fun displayPath(path: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < path.length) {
            if (path[i] != '{') {
                sb.append(path[i++])
                continue
            }
            var depth = 0
            var j = i
            while (j < path.length) {
                if (path[j] == '{') depth++
                if (path[j] == '}' && --depth == 0) break
                j++
            }
            sb.append('{').append(path.substring(i + 1, minOf(j, path.length)).substringBefore(':')).append('}')
            i = j + 1
        }
        return sb.toString()
    }

    /** The local stand URL: the first concrete host (`raketa.in`, not `master.*`), or just the path without one. */
    fun url(route: Route): String {
        val host = route.hosts.firstOrNull { !it.contains('*') } ?: return displayPath(route.path)
        return "https://$host${displayPath(route.path)}"
    }

    /** `POST /v3/chat/service/{serviceId}` */
    fun title(route: Route): String =
        listOf(route.methods.joinToString("|").ifEmpty { "ANY" }, displayPath(route.path)).joinToString(" ")
}
