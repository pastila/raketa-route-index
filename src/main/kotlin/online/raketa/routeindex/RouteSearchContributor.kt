package online.raketa.routeindex

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import java.nio.file.Path
import javax.swing.JList
import javax.swing.ListCellRenderer

/** A found route or console command, or the "index is missing — build it" hint. */
sealed interface RouteItem {
    data class Found(val match: RouteMatch) : RouteItem
    data class Command(val match: CommandMatch) : RouteItem
    data object BuildIndex : RouteItem
}

class RouteSearchContributor(private val project: Project) : WeightedSearchEverywhereContributor<RouteItem> {
    override fun getSearchProviderId(): String = ID
    override fun getGroupName(): String = "Routes"
    override fun getSortWeight(): Int = 150
    override fun showInFindResults(): Boolean = false
    override fun isShownInSeparateTab(): Boolean = true
    override fun isEmptyPatternSupported(): Boolean = false
    override fun getDataForItem(element: RouteItem, dataId: String): Any? = null

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<RouteItem>>,
    ) {
        if (pattern.trim().length < 3) return
        val index = RouteIndex.getInstance(project)
        if (index.snapshot() == null) {
            if (RouteQuery.parse(pattern).looksLikeUrl || COMMAND_NAME.containsMatchIn(pattern)) {
                consumer.process(FoundItemDescriptor(RouteItem.BuildIndex, 0))
            }
            return
        }
        for (match in index.search(pattern)) {
            progressIndicator.checkCanceled()
            if (!consumer.process(FoundItemDescriptor(RouteItem.Found(match), match.score))) return
        }
        for (match in index.searchCommands(pattern)) {
            progressIndicator.checkCanceled()
            if (!consumer.process(FoundItemDescriptor(RouteItem.Command(match), match.score))) return
        }
    }

    override fun processSelectedItem(selected: RouteItem, modifiers: Int, searchText: String): Boolean {
        when (selected) {
            is RouteItem.BuildIndex -> RefreshRoutesAction.refresh(project)
            is RouteItem.Found -> navigate(project, selected.match.route.route.file, selected.match.route.route.line)
            is RouteItem.Command -> navigate(project, selected.match.command.file, selected.match.command.line)
        }
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in RouteItem> = object : ColoredListCellRenderer<RouteItem>() {
        override fun customizeCellRenderer(
            list: JList<out RouteItem>,
            value: RouteItem,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            when (value) {
                is RouteItem.BuildIndex -> {
                    icon = AllIcons.Actions.Refresh
                    append("Индекс маршрутов не собран — Enter, чтобы собрать", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is RouteItem.Found -> {
                    val route = value.match.route.route
                    icon = AllIcons.Nodes.Method
                    val methods = route.methods.joinToString("|").ifEmpty { "ANY" }
                    append("$methods ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(route.path)
                    append("  ${shortHandler(route.handler)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${route.app}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    toolTipText = route.handler
                }
                is RouteItem.Command -> {
                    val command = value.match.command
                    icon = AllIcons.Nodes.Console
                    append("${command.name} ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(command.handler?.let { shortHandler(it) } ?: "класс не найден", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${command.entries.joinToString(", ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    toolTipText = listOfNotNull(command.handler, command.description.ifEmpty { null }).joinToString("\n")
                }
            }
        }
    }

    class Factory : SearchEverywhereContributorFactory<RouteItem> {
        override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<RouteItem> =
            RouteSearchContributor(initEvent.project!!)

        override fun isAvailable(project: Project?): Boolean = project != null
    }

    companion object {
        const val ID = "RaketaRoutes"

        /** `bus:translate-stations` — worth offering to build the index for. */
        private val COMMAND_NAME = Regex("""\b[a-z][\w-]*:[\w:-]+""")

        /** `Ddd\Client\Profile\...\RemarkController::get` → `RemarkController::get` */
        fun shortHandler(handler: String): String = handler.substringAfterLast('\\')

        fun navigate(project: Project, file: String?, line: Int) {
            val base = project.basePath ?: return
            if (file == null) return
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(base, file)) ?: return
            OpenFileDescriptor(project, vFile, maxOf(line - 1, 0), 0).navigate(true)
        }
    }
}
