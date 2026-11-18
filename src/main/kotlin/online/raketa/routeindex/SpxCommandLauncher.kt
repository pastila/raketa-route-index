package online.raketa.routeindex

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project

/**
 * Uses the SPX plugin's own runner, interpreter and report viewer. Its configuration classes are internal;
 * the platform's persisted options let us create one without linking to optional SPX classes.
 */
internal object SpxCommandLauncher {
    const val EXECUTOR_ID = "RaketaSpxProfile"
    private const val CONFIGURATION_TYPE_ID = "RaketaSpxPhpScript"

    private fun configurationType(): ConfigurationType? =
        phpScriptConfigurationType(CONFIGURATION_TYPE_ID)

    fun executor(): Executor? =
        if (configurationType() == null) null else ExecutorRegistry.getInstance().getExecutorById(EXECUTOR_ID)

    fun configuration(project: Project, target: CommandRunTarget, initialArguments: () -> String): CommandLaunchConfiguration? {
        val type = configurationType() ?: return null
        val name = "${target.configurationName} (SPX)"
        return phpScriptConfiguration(project, type, name, target, initialArguments)
    }
}
