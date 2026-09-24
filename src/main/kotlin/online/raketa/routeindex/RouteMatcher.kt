package online.raketa.routeindex

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A route with its path pre-compiled into one regex per segment. */
class CompiledRoute(val route: Route) {
    private val rawSegments = splitPath(route.path)

    /** `/static/photo/{rest:.+}` or Symfony `{path}` with requirement `.*`: the last placeholder eats the rest. */
    val restTail: Regex? = rawSegments.lastOrNull()
        ?.let { REST.matchEntire(it) }
        ?.let { m -> (m.groupValues[2].ifEmpty { route.requirements[m.groupValues[1]] ?: "" }) }
        ?.takeIf { it.contains(".*") || it.contains(".+") }
        ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    val segments: List<Regex> = (if (restTail != null) rawSegments.dropLast(1) else rawSegments)
        .map { compileSegment(it, route.requirements) }
    val literalSegments: Int = rawSegments.count { !it.contains('{') }
    val searchText: String = listOfNotNull(route.path, route.handler, route.name, route.app)
        .joinToString(" ")
        .lowercase()

    companion object {
        private val REST = Regex("""^\{(\w+)(?::(.*))?}$""")

        fun splitPath(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        /** `{id}.json` → `(?:[^/]+)\.json`; `{id:\d+}` or requirements[id] replace the default. */
        fun compileSegment(segment: String, requirements: Map<String, String>): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c != '{') {
                    val next = segment.indexOf('{', i).let { if (it < 0) segment.length else it }
                    sb.append(Regex.escape(segment.substring(i, next)))
                    i = next
                    continue
                }
                // Find the matching brace, the inline regex may contain its own {n,m}.
                var depth = 0
                var j = i
                while (j < segment.length) {
                    if (segment[j] == '{') depth++
                    if (segment[j] == '}') depth--
                    if (depth == 0) break
                    j++
                }
                val body = segment.substring(i + 1, minOf(j, segment.length))
                val name = body.substringBefore(':')
                val inline = if (body.contains(':')) body.substringAfter(':') else null
                val req = inline ?: requirements[name] ?: "[^/]+"
                sb.append("(?:").append(req).append(')')
                i = j + 1
            }
            return try {
                Regex(sb.toString(), RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                Regex(Regex.escape(segment), RegexOption.IGNORE_CASE)
            }
        }
    }
}

/** What the user typed into Search Everywhere, parsed as a URL when it looks like one. */
data class RouteQuery(val method: String?, val host: String?, val segments: List<String>, val text: String) {
    val looksLikeUrl: Boolean get() = segments.isNotEmpty() && (host != null || text.contains('/'))

    companion object {
        private val HOST = Regex("^[\\w-]+(\\.[\\w-]+)*\\.(in|online|ru|com|cnt|consul|local)(:\\d+)?$", RegexOption.IGNORE_CASE)
        /** A query segment that matches any route segment — see FeatureStepUrl.queryUrl */
        const val ANY_SEGMENT = "\u0000"

        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        fun parse(raw: String): RouteQuery {
            var s = raw.trim()
            var method: String? = null
            val firstWord = s.substringBefore(' ')
            if (firstWord.uppercase() in METHODS && s.length > firstWord.length) {
                method = firstWord.uppercase()
                s = s.substring(firstWord.length).trim()
            }
            val text = s
            var host: String? = null
            val scheme = s.indexOf("://")
            if (scheme >= 0) {
                s = s.substring(scheme + 3)
                host = s.substringBefore('/').substringBefore(':').lowercase()
                s = if (s.contains('/')) s.substring(s.indexOf('/')) else ""
            }
            s = s.substringBefore('?').substringBefore('#')
            // `fcc.raketa.in/directory/...` pasted without a scheme
            if (host == null && !s.startsWith('/') && s.contains('/') && HOST.matches(s.substringBefore('/'))) {
                host = s.substringBefore('/').substringBefore(':').lowercase()
                s = s.substring(s.indexOf('/'))
            }
            val segments = s.split('/')
                .filter { it.isNotEmpty() }
                .map { decode(it) }
            return RouteQuery(method, host, segments, text)
        }

        private fun decode(segment: String): String = try {
            URLDecoder.decode(segment, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            segment
        }
    }
}

object RouteMatcher {
    fun match(route: CompiledRoute, query: RouteQuery): RouteMatch? {
        urlMatch(route, query)?.let { return it }
        return textMatch(route, query)
    }

    private val LANG = Regex("^(ru|en)$", RegexOption.IGNORE_CASE)

    private fun urlMatch(route: CompiledRoute, query: RouteQuery): RouteMatch? {
        if (!query.looksLikeUrl) return null
        var qs = query.segments
        // `dir/index.php` is served as `dir/` (legacy fcc/planeta)
        if (qs.lastOrNull()?.equals("index.php", ignoreCase = true) == true) qs = qs.dropLast(1)
        segmentsMatch(route, query, qs)?.let { return it }
        // fcc/planeta and the SPA accept an optional language prefix: /ru/directory/...
        if (qs.size > 1 && LANG.matches(qs[0])) return segmentsMatch(route, query, qs.drop(1))
        return null
    }

    private fun segmentsMatch(route: CompiledRoute, query: RouteQuery, qs: List<String>): RouteMatch? {
        if (qs.isEmpty()) return null
        route.restTail?.let { return restMatch(route, query, qs, it) }
        var rs = route.segments
        // A last placeholder that accepts "" (`{child}` with `(a|b)?`) is optional: `/x/1/logs/`
        if (qs.size == rs.size - 1 && rs.last().matches("")) rs = rs.dropLast(1)
        if (qs.size > rs.size) return null
        val offset = rs.size - qs.size
        for (i in qs.indices) {
            if (!segmentMatches(rs[offset + i], qs[i])) return null
        }
        val kind = if (offset == 0) RouteMatch.Kind.URL else RouteMatch.Kind.URL_SUFFIX
        var score = if (offset == 0) 1000 else 700 - offset * 10
        score += route.literalSegments * 5
        score += hostBonus(route.route, query.host)
        score += methodBonus(route.route, query.method)
        return RouteMatch(route, score, kind)
    }

    private fun segmentMatches(route: Regex, query: String) = query == RouteQuery.ANY_SEGMENT || route.matches(query)

    /** Catch-all routes match only from the start of the path and lose to any concrete route. */
    private fun restMatch(route: CompiledRoute, query: RouteQuery, qs: List<String>, rest: Regex): RouteMatch? {
        val rs = route.segments
        if (qs.size <= rs.size) return null
        for (i in rs.indices) {
            if (!segmentMatches(rs[i], qs[i])) return null
        }
        if (!rest.matches(qs.drop(rs.size).joinToString("/"))) return null
        val score = 500 + route.literalSegments * 5 + hostBonus(route.route, query.host) + methodBonus(route.route, query.method)
        return RouteMatch(route, score, RouteMatch.Kind.URL)
    }

    private fun textMatch(route: CompiledRoute, query: RouteQuery): RouteMatch? {
        val tokens = query.text.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (!tokens.all { route.searchText.contains(it) }) return null
        var score = 300
        if (route.route.path.lowercase().endsWith(tokens.last())) score += 20
        score += methodBonus(route.route, query.method)
        return RouteMatch(route, score, RouteMatch.Kind.TEXT)
    }

    /**
     * The same path may be served by several apps (raketa.in/api vs agency API), so a host in the
     * query pushes its app up. Stage hosts (`avia.<stage domain>`) share the first label with
     * local ones (`avia.raketa.in`).
     */
    private fun hostBonus(route: Route, host: String?): Int {
        if (host == null || route.hosts.isEmpty()) return 0
        if (route.hosts.any { it.equals(host, ignoreCase = true) }) return 100
        val label = host.substringBefore('.')
        if (route.hosts.any { it.substringBefore('.').equals(label, ignoreCase = true) }) return 80
        return -50
    }

    private fun methodBonus(route: Route, method: String?): Int = when {
        method == null || route.methods.isEmpty() -> 0
        route.methods.any { it.equals(method, ignoreCase = true) } -> 50
        else -> -100
    }
}
