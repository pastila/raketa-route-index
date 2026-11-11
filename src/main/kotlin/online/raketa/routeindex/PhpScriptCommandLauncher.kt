package online.raketa.routeindex

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.jetbrains.php.run.script.PhpScriptRunConfiguration
import com.jetbrains.php.run.script.PhpScriptRunConfigurationType

/**
 * A PHP Script configuration on the project's default interpreter (the Docker one in raketa), so Debug listens
 * for Xdebug by itself. Registered only when PhpStorm's PHP plugin is there.
 */
class PhpScriptCommandLauncher : CommandLauncher {
    override fun configuration(project: Project, target: CommandRunTarget): CommandLaunchConfiguration? {
        val base = project.basePath ?: return null
        val runManager = RunManager.getInstance(project)
        val type = PhpScriptRunConfigurationType.getInstance()
        val settings = runManager.findConfigurationByTypeAndName(type, target.configurationName) ?: run {
            val created = runManager.createConfiguration(target.configurationName, type.configurationFactories.first())
            val php = (created.configuration as PhpScriptRunConfiguration).settings
            php.setPath("$base/${target.entry}")
            php.setScriptParameters(target.command.name)
            php.workingDirectory = "$base/${target.workingDirectory}".trimEnd('/')
            runManager.setTemporaryConfiguration(created)
            created
        }
        val php = (settings.configuration as PhpScriptRunConfiguration).settings
        return object : CommandLaunchConfiguration {
            override val settings: RunnerAndConfigurationSettings = settings
            override var arguments: String
                get() = php.scriptParameters.orEmpty()
                set(value) { php.setScriptParameters(value) }
        }
    }
}
