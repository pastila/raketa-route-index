package online.raketa.routeindex

import com.intellij.execution.Executor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Edits the same script parameters as Edit Configurations, including the command name. */
class CommandArgumentsDialog(
    project: Project,
    target: CommandRunTarget,
    executor: Executor,
    initialArguments: String,
) : DialogWrapper(project) {
    private val editor = RawCommandLineEditor().apply { text = initialArguments }

    val arguments: String get() = editor.text

    init {
        title = "${executor.actionName}: ${target.configurationName}"
        setOKButtonText(commandActionText(executor))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Команда, аргументы и опции:") {
            cell(editor).align(AlignX.FILL)
                .comment("Сохраните имя команды в начале строки. Значения с пробелами заключайте в кавычки.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = editor.textField

    override fun doValidate(): ValidationInfo? =
        if (arguments.isBlank()) ValidationInfo("Укажите команду", editor.textField) else null
}
