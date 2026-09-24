package online.raketa.routeindex

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Ctrl+click on a URL in a behat step jumps to its controller. Only the URL itself is a link, so
 * a click on the rest of the step still goes to the step definition (Behat plugin).
 */
class FeatureUrlGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = sourceElement?.containingFile?.virtualFile ?: return null
        if (file.extension != "feature") return null
        val project = editor.project ?: return null
        val hit = hitAt(project, editor, offset, file.path) ?: return null

        val routes = RouteIndex.getInstance(project).resolve(hit.app, hit.method, FeatureStepUrl.queryUrl(hit.url))
        return routes.mapNotNull { target(project, it) }.distinct().toTypedArray().ifEmpty { null }
    }

    private fun hitAt(project: Project, editor: Editor, offset: Int, path: String): FeatureStepUrl.Hit? {
        val document = editor.document
        if (offset > document.textLength) return null
        val lineIndex = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(lineIndex)
        val lines = document.charsSequence.lines()
        val relative = project.basePath?.let { path.removePrefix("$it/") } ?: path
        return FeatureStepUrl.find(lines, lineIndex, column, relative)
    }

    companion object {
        /** The method (or class) declared on the route's line, so the chooser popup shows its name. */
        fun target(project: Project, route: Route): PsiElement? {
            val base = project.basePath ?: return null
            val relative = route.file ?: return null
            val vFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(base, relative)) ?: return null
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return psiFile
            val lineIndex = (route.line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
            val range = TextRange(document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
            val text = document.getText(range)
            val leaf = psiFile.findElementAt(range.startOffset + (text.length - text.trimStart().length)) ?: return psiFile
            val named = PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
            // PsiFile is a PsiNamedElement too — it would send us to the top of the file.
            return named?.takeIf { it !is PsiFile && it.textRange.intersects(range) } ?: leaf
        }
    }
}
