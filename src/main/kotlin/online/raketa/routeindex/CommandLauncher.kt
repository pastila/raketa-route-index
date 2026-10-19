package online.raketa.routeindex

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Creates the run configuration behind a gutter ▶ of a console command. PhpStorm registers the PHP Script one
 * (`php-run.xml`, loaded only with `com.jetbrains.php`); other IDEs, such as Raketa with the OpenIDE PHP plugin,
 * fall back to [ConsoleCommandConfigurationType], which runs the console with `docker exec`.
 */
interface CommandLauncher {
    /** An existing configuration named [CommandRunTarget.configurationName] or a new temporary one; null to defer. */
    fun configuration(project: Project, target: CommandRunTarget): RunnerAndConfigurationSettings?

    companion object {
        private val EP_NAME = ExtensionPointName<CommandLauncher>("online.raketa.routeindex.commandLauncher")

        fun configuration(project: Project, target: CommandRunTarget): RunnerAndConfigurationSettings? =
            EP_NAME.extensionList.firstNotNullOfOrNull { it.configuration(project, target) }
                ?: ConsoleCommandConfigurationType.configuration(project, target)
    }
}
