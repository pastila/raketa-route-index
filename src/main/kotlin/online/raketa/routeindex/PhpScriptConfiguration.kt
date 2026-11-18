package online.raketa.routeindex

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import org.jdom.Element

internal fun phpScriptConfigurationType(id: String): ConfigurationType? =
    ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull { it.id == id }

internal fun phpScriptConfiguration(
    project: Project,
    type: ConfigurationType,
    name: String,
    target: CommandRunTarget,
    initialArguments: () -> String,
): CommandLaunchConfiguration {
    val runManager = RunManager.getInstance(project)
    val settings = runManager.findConfigurationByTypeAndName(type, name) ?: run {
        val created = runManager.createConfiguration(name, type.configurationFactories.first())
        val state = state(created.configuration)
        PhpScriptOptions(state).initialize(target, initialArguments())
        created.configuration.readExternal(state)
        runManager.setTemporaryConfiguration(created)
        created
    }
    return object : CommandLaunchConfiguration {
        override val settings: RunnerAndConfigurationSettings = settings
        override var arguments: String
            get() = PhpScriptOptions(state(settings.configuration)).arguments
            set(value) {
                val state = state(settings.configuration)
                PhpScriptOptions(state).arguments = value
                settings.configuration.readExternal(state)
            }
    }
}

private fun state(configuration: RunConfiguration): Element =
    Element("configuration").also { configuration.writeExternal(it) }

/** Persisted options shared by the Raketa PHP and SPX script configurations. */
internal class PhpScriptOptions(private val state: Element) {
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
