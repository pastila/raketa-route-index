package online.raketa.routeindex

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import org.jdom.Element

/**
 * Uses the SPX plugin's own runner, interpreter and report viewer. Its configuration classes are internal;
 * the platform's persisted options let us create one without linking to optional SPX classes.
 */
internal object SpxCommandLauncher {
    const val EXECUTOR_ID = "RaketaSpxProfile"
    private const val CONFIGURATION_TYPE_ID = "RaketaSpxPhpScript"

    private fun configurationType(): ConfigurationType? =
        ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull { it.id == CONFIGURATION_TYPE_ID }

    fun executor(): Executor? =
        if (configurationType() == null) null else ExecutorRegistry.getInstance().getExecutorById(EXECUTOR_ID)

    fun configuration(project: Project, target: CommandRunTarget, initialArguments: () -> String): CommandLaunchConfiguration? {
        val type = configurationType() ?: return null
        val runManager = RunManager.getInstance(project)
        val name = "${target.configurationName} (SPX)"
        val settings = runManager.findConfigurationByTypeAndName(type, name) ?: run {
            val created = runManager.createConfiguration(name, type.configurationFactories.first())
            val state = state(created.configuration)
            SpxCommandOptions(state).initialize(target, initialArguments())
            created.configuration.readExternal(state)
            runManager.setTemporaryConfiguration(created)
            created
        }
        return object : CommandLaunchConfiguration {
            override val settings: RunnerAndConfigurationSettings = settings
            override var arguments: String
                get() = SpxCommandOptions(state(settings.configuration)).arguments
                set(value) {
                    val state = state(settings.configuration)
                    SpxCommandOptions(state).arguments = value
                    settings.configuration.readExternal(state)
                }
        }
    }

    private fun state(configuration: RunConfiguration): Element =
        Element("configuration").also { configuration.writeExternal(it) }
}

/** Option names are the serialization contract of SPX's SpxPhpScriptOptions. Keep other settings intact. */
internal class SpxCommandOptions(private val state: Element) {
    var arguments: String
        get() = option("arguments")?.getAttributeValue("value").orEmpty()
        set(value) { setOption("arguments", value) }

    fun initialize(target: CommandRunTarget, arguments: String) {
        setOption("scriptPath", target.entry)
        setOption("workingDirectory", target.workingDirectory.ifEmpty { "." })
        this.arguments = arguments
    }

    private fun option(name: String): Element? = state.getChildren("option").firstOrNull { it.getAttributeValue("name") == name }

    private fun setOption(name: String, value: String) {
        val option = option(name) ?: Element("option").setAttribute("name", name).also { state.addContent(it) }
        option.setAttribute("value", value)
    }
}
