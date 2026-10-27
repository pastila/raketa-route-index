package online.raketa.routeindex

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.awt.RelativePoint
import javax.swing.JList

/**
 * A second icon next to the route icon of a controller method: opens its request in the scratch file
 * `raketa-<app>.http`, where the HTTP client runs it. The `.http` format is the same in the HTTP Client of PhpStorm
 * and in the one of Raketa, so the plugin depends on neither; the icon is shown when some plugin opens `.http` files.
 */
class HttpRequestLineMarkerProvider : LineMarkerProvider, DumbAware {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val targets = RouteLineMarkerProvider.routesAt(element)
        if (targets.isEmpty() || !isHttpClientAvailable()) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.FileTypes.Http,
            { _ -> "Открыть в HTTP-клиенте:<br>" + targets.joinToString("<br>") { RouteTarget.title(it) } },
            { event, elt ->
                val current = RouteLineMarkerProvider.routesAt(elt).ifEmpty { targets }
                if (current.size == 1) {
                    open(elt.project, current.single())
                } else {
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(current)
                        .setTitle("Открыть в HTTP-клиенте")
                        .setRenderer(object : ColoredListCellRenderer<Route>() {
                            override fun customizeCellRenderer(list: JList<out Route>, route: Route, index: Int, selected: Boolean, hasFocus: Boolean) {
                                append(RouteTarget.title(route))
                            }
                        })
                        .setItemChosenCallback { open(elt.project, it) }
                        .createPopup()
                        .show(RelativePoint(event))
                }
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Открыть в HTTP-клиенте" },
        )
    }

    companion object {
        /** True when `.http` files have a file type of their own: the HTTP Client of PhpStorm or of Raketa is on. */
        fun isHttpClientAvailable(): Boolean {
            val type = FileTypeManager.getInstance().getFileTypeByFileName("requests.http")
            return type != FileTypes.UNKNOWN && type != FileTypes.PLAIN_TEXT
        }

        /** Adds the request of [route] to `raketa-<app>.http` unless it is there already, and puts the caret on its request line. */
        fun open(project: Project, route: Route) {
            val name = "raketa-${route.app}.http"
            val scratch = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), name, ScratchFileService.Option.existing_only)
                ?: ScratchRootType.getInstance().createScratchFile(project, name, null, "", ScratchFileService.Option.create_new_always)
                ?: return
            val document = FileDocumentManager.getInstance().getDocument(scratch) ?: return
            var offset = HttpRequestText.offset(document.text, route)
            if (offset < 0) {
                WriteCommandAction.runWriteCommandAction(project, "Добавить запрос", null, {
                    val text = document.text
                    val separator = if (text.isEmpty() || text.endsWith("\n\n")) "" else if (text.endsWith("\n")) "\n" else "\n\n"
                    offset = text.length + separator.length
                    document.insertString(text.length, separator + HttpRequestText.request(route))
                })
                FileDocumentManager.getInstance().saveDocument(document)
            }
            // The run button of the HTTP client is on the request line.
            val requestLine = document.text.indexOf("\n${HttpRequestText.method(route)} ", offset)
            OpenFileDescriptor(project, scratch, if (requestLine < 0) offset else requestLine + 1).navigate(true)
        }
    }
}
