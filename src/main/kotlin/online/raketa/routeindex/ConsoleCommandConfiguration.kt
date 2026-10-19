package online.raketa.routeindex

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.showRunContent
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.concurrency.resolvedPromise
import javax.swing.JComponent

/** `Debug`: DefaultDebugExecutor lives in the debugger module, which isn't on the plugin's classpath. */
private const val DEBUG_EXECUTOR_ID = "Debug"

/**
 * A console command run with `docker exec` in the php container — the ▶ of IDEs without PhpStorm's PHP plugin.
 * Debug only asks Xdebug for a session (`XDEBUG_SESSION`); the PHP plugin of the IDE accepts it when it listens.
 */
class ConsoleCommandConfigurationType : SimpleConfigurationType(
    "RaketaConsoleCommand",
    "Raketa Console Command",
    "Runs a console command of a Raketa app in the php container",
    NotNullLazyValue.createValue { AllIcons.Nodes.Console },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        ConsoleCommandConfiguration(project, this, "Raketa Console Command")

    override fun getOptionsClass(): Class<out BaseState> = ConsoleCommandOptions::class.java

    companion object {
        fun configuration(project: Project, target: CommandRunTarget): RunnerAndConfigurationSettings {
            val runManager = RunManager.getInstance(project)
            val type = ConfigurationTypeUtil.findConfigurationType(ConsoleCommandConfigurationType::class.java)
            runManager.findConfigurationByTypeAndName(type, target.configurationName)?.let { return it }

            val settings = runManager.createConfiguration(target.configurationName, type)
            val options = (settings.configuration as ConsoleCommandConfiguration).options
            options.container = RefreshRoutesAction.container
            options.entry = target.entry
            options.workingDirectory = target.workingDirectory
            options.arguments = target.command.name
            runManager.setTemporaryConfiguration(settings)
            return settings
        }
    }
}

class ConsoleCommandOptions : RunConfigurationOptions() {
    var container by string()
    var entry by string()
    var workingDirectory by string()
    var arguments by string()
}

class ConsoleCommandConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ConsoleCommandOptions>(project, factory, name) {

    public override fun getOptions(): ConsoleCommandOptions = super.getOptions() as ConsoleCommandOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ConsoleCommandSettingsEditor()

    override fun checkConfiguration() {
        if (options.container.isNullOrBlank()) throw RuntimeConfigurationError("Не указан контейнер")
        if (options.entry.isNullOrBlank()) throw RuntimeConfigurationError("Не указана консоль")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val command = ConsoleCommandLine.docker(
                    container = options.container.orEmpty().ifBlank { RefreshRoutesAction.container },
                    root = RefreshRoutesAction.containerRoot,
                    entry = options.entry.orEmpty(),
                    workingDirectory = options.workingDirectory.orEmpty(),
                    arguments = ParametersListUtil.parse(options.arguments.orEmpty()),
                    debug = executor.id == DEBUG_EXECUTOR_ID,
                )
                val handler = KillableColoredProcessHandler(GeneralCommandLine(command).withCharset(Charsets.UTF_8))
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
}

/** The `docker exec` command line, apart from the platform for the tests. */
object ConsoleCommandLine {
    /**
     * `docker exec -w /web/core raketa-php php /web/core/bin/console bus:listen`. Run turns Xdebug off (with a
     * listening IDE every php process in the container would wait for it); Debug requests a session.
     */
    fun docker(container: String, root: String, entry: String, workingDirectory: String, arguments: List<String>, debug: Boolean): List<String> =
        buildList {
            add("docker"); add("exec")
            if (debug) {
                add("-e"); add("XDEBUG_SESSION=1")
                add("-e"); add("XDEBUG_MODE=debug")
            }
            add("-w"); add("$root/$workingDirectory".trimEnd('/'))
            add(container)
            add("php")
            if (!debug) {
                add("-d"); add("xdebug.mode=off")
            }
            add("$root/$entry")
            addAll(arguments)
        }
}

/** Starts [ConsoleCommandConfiguration] from the Debug action; the debugger itself is the PHP plugin's. */
class ConsoleCommandDebugRunner : ProgramRunner<RunnerSettings> {
    override fun getRunnerId(): String = "RaketaConsoleCommandDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DEBUG_EXECUTOR_ID && profile is ConsoleCommandConfiguration

    override fun execute(environment: ExecutionEnvironment) {
        val state = environment.state ?: return
        ExecutionManager.getInstance(environment.project).startRunProfile(environment) {
            FileDocumentManager.getInstance().saveAllDocuments()
            resolvedPromise(showRunContent(state.execute(environment.executor, this), environment))
        }
    }
}

private class ConsoleCommandSettingsEditor : SettingsEditor<ConsoleCommandConfiguration>() {
    private val container = JBTextField()
    private val entry = JBTextField()
    private val workingDirectory = JBTextField()
    private val arguments = RawCommandLineEditor()

    override fun createEditor(): JComponent = panel {
        row("Контейнер:") { cell(container).align(AlignX.FILL).comment("docker exec &lt;контейнер&gt;") }
        row("Консоль:") { cell(entry).align(AlignX.FILL).comment("Путь от корня проекта: core/bin/console") }
        row("Рабочий каталог:") { cell(workingDirectory).align(AlignX.FILL).comment("От корня проекта: core") }
        row("Аргументы:") { cell(arguments).align(AlignX.FILL) }
    }

    override fun resetEditorFrom(configuration: ConsoleCommandConfiguration) {
        val options = configuration.options
        container.text = options.container.orEmpty()
        entry.text = options.entry.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        arguments.text = options.arguments.orEmpty()
    }

    override fun applyEditorTo(configuration: ConsoleCommandConfiguration) {
        val options = configuration.options
        options.container = container.text.trim()
        options.entry = entry.text.trim()
        options.workingDirectory = workingDirectory.text.trim()
        options.arguments = arguments.text
    }
}
