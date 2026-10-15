package online.raketa.routeindex

/**
 * Steps of behat `.feature` files whose URL resolves to one of the given routes — the same resolution as
 * Ctrl+click on the step, so the two directions agree.
 */
object FeatureUsages {
    data class Usage(val file: String, val line: Int, val text: String, val route: Route)

    /**
     * @param routes every route of the index (a URL resolves to the best-scored ones among all of them)
     * @param targets the routes to look for
     * @param file project-relative path of the feature
     * @param text its contents
     */
    fun inFile(routes: List<CompiledRoute>, targets: Collection<Route>, file: String, text: String): List<Usage> {
        if (targets.isEmpty() || !mentionsAny(text, targets)) return emptyList()
        val lines = text.lines()
        val usages = mutableListOf<Usage>()
        for ((i, line) in lines.withIndex()) {
            if (!line.contains(" /")) continue
            val hit = FeatureStepUrl.find(lines, i, null, file) ?: continue
            val found = RouteIndex.resolveIn(routes, hit.app, hit.method, FeatureStepUrl.queryUrl(hit.url))
            found.firstOrNull { it in targets }?.let { usages += Usage(file, i + 1, line.trim(), it) }
        }
        return usages
    }

    /** Cheap filter: the file has the longest literal segment of some target path (or the path is all placeholders). */
    private fun mentionsAny(text: String, targets: Collection<Route>): Boolean = targets.any { route ->
        val literal = CompiledRoute.splitPath(route.path).filter { !it.contains('{') }.maxByOrNull { it.length }
        literal == null || text.contains(literal, ignoreCase = true)
    }
}
