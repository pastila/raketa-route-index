package online.raketa.routeindex

import com.intellij.openapi.project.Project

class RaketaPhpCommandLauncher : CommandLauncher {
    override fun configuration(project: Project, target: CommandRunTarget): CommandLaunchConfiguration? {
        val type = phpScriptConfigurationType("RaketaPhpScript") ?: return null
        return phpScriptConfiguration(project, type, target.configurationName, target) {
            ConsoleCommandConfigurationType.savedArguments(project, target) ?: target.command.name
        }
    }
}
