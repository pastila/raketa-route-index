package online.raketa.routeindex

/**
 * One way to start a console command: [command] through the console script [entry]
 * (`core/bin/console`, `api/hotels/cli/sabre`). The gutter ▶ on a command class offers one per entry.
 */
data class CommandRunTarget(val command: ConsoleCommand, val entry: String) {
    /** Run configuration name; the console is spelled out only when the command has several. */
    val configurationName: String
        get() = if (command.entries.size > 1) "${command.name} ($entry)" else command.name

    /** The app root of the console: `core/bin/console` → `core`, `api/hotels/cli/sabre` → `api/hotels`. */
    val workingDirectory: String
        get() = entry.substringBeforeLast('/', "").substringBeforeLast('/', "")

    companion object {
        /**
         * Targets of the commands whose handler is the method [methodName] declared in [file]. Matching by
         * the method name rather than the dumped line keeps the ▶ in place while the file is being edited.
         */
        fun forMethod(commands: List<ConsoleCommand>, file: String, methodName: String): List<CommandRunTarget> =
            commands.asSequence()
                .filter { it.file == file && it.handler?.substringAfter("::", "") == methodName }
                .sortedBy { it.name }
                .flatMap { command -> command.entries.map { CommandRunTarget(command, it) } }
                .toList()
    }
}
