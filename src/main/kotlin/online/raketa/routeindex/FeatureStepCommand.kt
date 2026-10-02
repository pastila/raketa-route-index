package online.raketa.routeindex

/**
 * The console command under the caret in a behat step that stubs it (ProcessExecutor):
 * `Дано команда "aeroexpress:booking --serviceId=1" вернёт код 0 и вывод "..."`. Only the name is
 * a link, a click on the arguments still goes to the step definition.
 */
object FeatureStepCommand {
    data class Hit(val name: String, val start: Int, val end: Int)

    private val STUB = Regex("""команда "([^"\s]+)[^"]*" вернёт код""")

    fun find(line: String, column: Int): Hit? {
        val name = STUB.find(line)?.groups?.get(1) ?: return null
        if (column !in name.range.first..name.range.last + 1) return null
        return Hit(name.value, name.range.first, name.range.last + 1)
    }
}
