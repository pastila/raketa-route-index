package online.raketa.routeindex

/**
 * Finds console commands by what the user typed or pasted: a bare name (`bus:translate-stations`),
 * a whole command line (`php api/hotels/cli/sabre sync:api:service --id=1`) or words of the name,
 * class or description.
 */
object CommandMatcher {
    private val WHITESPACE = Regex("\\s+")

    fun match(command: ConsoleCommand, raw: String): CommandMatch? {
        val tokens = raw.trim().split(WHITESPACE).map { it.trim('"', '\'') }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val names = listOf(command.name) + command.aliases
        if (tokens.any { token -> names.any { it.equals(token, ignoreCase = true) } }) {
            return CommandMatch(command, 1000 + entryBonus(command, tokens), exact = true)
        }
        val text = searchText(command)
        val words = tokens.map { it.lowercase() }
        if (!words.all { text.contains(it) }) return null
        var score = 300
        if (command.name.startsWith(words.first(), ignoreCase = true)) score += 20
        if (command.hidden) score -= 10
        return CommandMatch(command, score, exact = false)
    }

    /**
     * Same-named commands of different scripts (`sync:api:service` in every `api/hotels/cli` script): a
     * script path in the query picks its own.
     */
    private fun entryBonus(command: ConsoleCommand, tokens: List<String>): Int {
        val paths = tokens.filter { it.contains('/') && !it.startsWith("--") }
            .map { it.removePrefix("/web/").removePrefix("./").trimStart('.', '/') }
            .filter { it.isNotEmpty() }
        if (paths.isEmpty()) return 0
        val hit = paths.any { path -> command.entries.any { it == path || it.endsWith("/$path") || path.endsWith("/$it") } }
        return if (hit) 100 else -50
    }

    private fun searchText(command: ConsoleCommand): String =
        (listOf(command.name, command.handler.orEmpty(), command.description) + command.aliases + command.entries)
            .joinToString(" ")
            .lowercase()
}
