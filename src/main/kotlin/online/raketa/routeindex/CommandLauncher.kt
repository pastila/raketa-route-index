package online.raketa.routeindex

import com.intellij.execution.Executor
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Creates the run configuration behind a gutter ▶ of a console command. PhpStorm and Raketa PHP each register
 * a PHP Script launcher. [ConsoleCommandConfigurationType] runs the console with `docker exec` as a fallback.
 */
interface CommandLauncher {
    /** An existing configuration named [CommandRunTarget.configurationName] or a new temporary one; null to defer. */
    fun configuration(project: Project, target: CommandRunTarget): CommandLaunchConfiguration?

    companion object {
        private val EP_NAME = ExtensionPointName<CommandLauncher>("online.raketa.routeindex.commandLauncher")

        fun configuration(project: Project, target: CommandRunTarget): CommandLaunchConfiguration? =
            EP_NAME.extensionList.firstNotNullOfOrNull { it.configuration(project, target) }
                ?: ConsoleCommandConfigurationType.configuration(project, target)

        fun configuration(project: Project, target: CommandRunTarget, executor: Executor): CommandLaunchConfiguration? =
            if (executor.id == SpxCommandLauncher.EXECUTOR_ID) {
                SpxCommandLauncher.configuration(project, target) {
                    configuration(project, target)?.arguments ?: target.command.name
                }
            } else configuration(project, target)
    }
}

/** Script parameters shared by the command launchers. */
interface CommandLaunchConfiguration {
    val settings: RunnerAndConfigurationSettings
    var arguments: String
}

internal fun commandActionText(executor: Executor): String = when (executor.id) {
    "Run" -> "Запустить"
    "Debug" -> "Отладить"
    else -> executor.actionName
}
