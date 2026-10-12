package online.raketa.routeindex

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.run.script.PhpScriptRunConfiguration
import com.jetbrains.php.run.script.PhpScriptRunConfigurationType

/**
 * ▶ on `execute()`/`__invoke()` of every console command in the index, with Run and Debug through each
 * console that serves it — scripts of `api/<service>/cli` included, which the Symfony plugin's marker doesn't know.
 * Starts a PHP Script configuration on the project's default interpreter (the Docker one in raketa), so
 * Debug listens for Xdebug by itself. An existing configuration of the same name is reused as is, with
 * whatever arguments were added to it.
 */
class CommandRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null) return null
        val methodName = element.text
        if (methodName.isEmpty() || !methodName[0].isJavaIdentifierStart()) return null
        if (PsiTreeUtil.prevVisibleLeaf(element)?.text != "function") return null

        val project = element.project
        val base = project.basePath ?: return null
        val path = element.containingFile?.virtualFile?.path ?: return null
        if (!path.startsWith("$base/")) return null
        val commands = RouteIndex.getInstance(project).snapshot()?.commands ?: return null
        val targets = CommandRunTarget.forMethod(commands, path.removePrefix("$base/"), methodName)
        if (targets.isEmpty()) return null

        // DefaultDebugExecutor lives in the debugger module, which isn't on the plugin's classpath.
        val executors = listOfNotNull(DefaultRunExecutor.getRunExecutorInstance(), ExecutorRegistry.getInstance().getExecutorById("Debug"))
        val actions = targets.flatMap { target -> executors.map { RunCommandAction(target, it) } }
        return Info(AllIcons.RunConfigurations.TestState.Run, actions.toTypedArray()) { _ ->
            targets.map { it.command.name }.distinct().joinToString(", ", prefix = "Run ")
        }
    }

    private class RunCommandAction(private val target: CommandRunTarget, private val executor: Executor) :
        AnAction("${executor.actionName} '${target.configurationName}'", null, executor.icon), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val settings = configuration(project) ?: return
            RunManager.getInstance(project).selectedConfiguration = settings
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }

        private fun configuration(project: Project): RunnerAndConfigurationSettings? {
            val base = project.basePath ?: return null
            val runManager = RunManager.getInstance(project)
            val type = PhpScriptRunConfigurationType.getInstance()
            runManager.findConfigurationByTypeAndName(type, target.configurationName)?.let { return it }

            val settings = runManager.createConfiguration(target.configurationName, type.configurationFactories.first())
            val php = (settings.configuration as PhpScriptRunConfiguration).settings
            php.setPath("$base/${target.entry}")
            php.setScriptParameters(target.command.name)
            php.workingDirectory = "$base/${target.workingDirectory}".trimEnd('/')
            runManager.setTemporaryConfiguration(settings)
            return settings
        }
    }
}
