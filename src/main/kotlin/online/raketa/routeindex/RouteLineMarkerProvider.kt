package online.raketa.routeindex

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import java.awt.datatransfer.StringSelection
import javax.swing.JList

/**
 * An icon on every controller method in the index — the reverse of the URL search. The tooltip lists its
 * routes; a click offers to copy each URL (local stand host) or path, to go to where the route is declared,
 * and to find the behat steps that call it.
 */
class RouteLineMarkerProvider : LineMarkerProvider, DumbAware {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val methodName = element.text
        if (methodName.isEmpty() || !methodName[0].isJavaIdentifierStart()) return null
        if (PsiTreeUtil.prevVisibleLeaf(element)?.text != "function") return null

        val project = element.project
        val file = relativePath(project, element) ?: return null
        val routes = RouteIndex.getInstance(project).snapshot()?.routes?.map { it.route } ?: return null
        val targets = RouteTarget.forMethod(routes, file, methodName)
        if (targets.isEmpty()) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Nodes.PpWeb,
            { _ -> targets.joinToString("<br>") { RouteTarget.title(it) } },
            { event, elt ->
                // Re-read on click: the index may have been rebuilt since the marker was drawn.
                val current = relativePath(elt.project, elt)?.let { path ->
                    RouteIndex.getInstance(elt.project).snapshot()?.routes?.map { it.route }
                        ?.let { RouteTarget.forMethod(it, path, methodName) }
                }.orEmpty().ifEmpty { targets }
                JBPopupFactory.getInstance()
                    .createActionGroupPopup(null, actions(elt.project, current), DataManager.getInstance().getDataContext(event.component), ActionSelectionAid.SPEEDSEARCH, false)
                    .show(RelativePoint(event))
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Raketa route" },
        )
    }

    private fun relativePath(project: Project, element: PsiElement): String? {
        val base = project.basePath ?: return null
        val path = element.containingFile?.virtualFile?.path ?: return null
        return path.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")
    }

    private fun actions(project: Project, routes: List<Route>): DefaultActionGroup {
        val group = DefaultActionGroup()
        for (route in routes) {
            if (routes.size > 1) group.add(Separator.create(RouteTarget.title(route)))
            val url = RouteTarget.url(route)
            group.add(CopyAction("Копировать URL $url", url))
            val path = RouteTarget.displayPath(route.path)
            if (path != url) group.add(CopyAction("Копировать путь $path", path))
            route.declarationFile?.let { file ->
                group.add(object : AnAction("Перейти к объявлению ${file.substringAfterLast('/')}:${route.declarationLine}", file, AllIcons.Actions.Forward), DumbAware {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun actionPerformed(e: AnActionEvent) = RouteSearchContributor.navigate(project, file, route.declarationLine)
                })
            }
        }
        group.add(Separator.create())
        group.add(object : AnAction("Найти в feature-файлах", null, AllIcons.Actions.Find), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = findUsages(project, routes)
        })
        return group
    }

    private class CopyAction(text: String, private val value: String) : AnAction(text, null, AllIcons.Actions.Copy), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(value))
    }

    private fun findUsages(project: Project, targets: List<Route>) {
        val base = project.basePath ?: return
        val routes = RouteIndex.getInstance(project).snapshot()?.routes ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Поиск маршрута в feature-файлах", true) {
            private var usages: List<FeatureUsages.Usage> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val files = runReadActionBlocking {
                    FilenameIndex.getAllFilesByExt(project, "feature", GlobalSearchScope.projectScope(project)).toList()
                }.filter { it.path.startsWith("$base/") && !it.path.contains("/vendor/") }
                indicator.isIndeterminate = false
                usages = files.flatMapIndexed { i, vFile ->
                    indicator.checkCanceled()
                    indicator.fraction = i.toDouble() / files.size
                    val text = runCatching { String(vFile.contentsToByteArray(), vFile.charset) }.getOrNull() ?: return@flatMapIndexed emptyList()
                    FeatureUsages.inFile(routes, targets, vFile.path.removePrefix("$base/"), text)
                }.sortedWith(compareBy({ it.file }, { it.line }))
            }

            override fun onSuccess() = showUsages(project, targets, usages)
        })
    }

    private fun showUsages(project: Project, targets: List<Route>, usages: List<FeatureUsages.Usage>) {
        if (usages.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup("Raketa Routes")
                .createNotification("Маршрут не вызывается в feature-файлах", targets.joinToString("<br>") { RouteTarget.title(it) }, NotificationType.INFORMATION)
                .notify(project)
            return
        }
        if (usages.size == 1) {
            usages.single().let { RouteSearchContributor.navigate(project, it.file, it.line) }
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(usages)
            .setTitle("Feature-файлы: ${usages.size} (${usages.map { it.file }.distinct().size} файлов)")
            .setRenderer(object : ColoredListCellRenderer<FeatureUsages.Usage>() {
                override fun customizeCellRenderer(list: JList<out FeatureUsages.Usage>, usage: FeatureUsages.Usage, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append("${usage.file.substringAfterLast("/Features/").substringAfterLast("/features/")}:${usage.line}  ")
                    append(usage.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { "${it.file} ${it.text}" }
            .setItemChosenCallback { RouteSearchContributor.navigate(project, it.file, it.line) }
            .createPopup()
            .showInFocusCenter()
    }
}
