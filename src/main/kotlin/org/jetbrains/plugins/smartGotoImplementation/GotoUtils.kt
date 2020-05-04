package org.jetbrains.plugins.smartGotoImplementation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.ide.util.EditSourceUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.sun.jdi.ClassType
import com.sun.jdi.Type

internal fun PsiMethod.gotoMethod(editor: Editor, file: PsiFile) {
    TargetElementUtil.getInstance()
            .getGotoDeclarationTarget(this, navigationElement)
            ?.let { gotoTargetElement(it, editor, file) }
}

private fun gotoTargetElement(element: PsiElement, currentEditor: Editor, currentFile: PsiFile) {
    if (navigateInCurrentEditor(element, currentFile, currentEditor)) return
    val navigatable = if (element is Navigatable) element else EditSourceUtil.getDescriptor(element)
    if (navigatable != null && navigatable.canNavigate()) {
        navigatable.navigate(true)
    }
}

private fun navigateInCurrentEditor(element: PsiElement, currentFile: PsiFile, currentEditor: Editor): Boolean {
    if (element.containingFile === currentFile && !currentEditor.isDisposed) {
        val offset = element.textOffset
        val leaf = currentFile.findElementAt(offset)
        // check that element is really physically inside the file
        // there are fake elements with custom navigation (e.g. opening URL in browser) that override getContainingFile for various reasons
        if (leaf != null && PsiTreeUtil.isAncestor(element, leaf, false)) {
            val project = element.project
            CommandProcessor.getInstance().executeCommand(project, {
                IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
                OpenFileDescriptor(project, currentFile.viewProvider.virtualFile, offset).navigateIn(currentEditor)
            }, "", null)
            return true
        }
    }
    return false
}

internal fun findClassByDebuggerType(type: Type, debugProcess: DebugProcess, project: Project): PsiClass? = readPlease {
    val targetClass = type.name()?.let { DebuggerUtils.findClass(it, project, GlobalSearchScope.allScope(project)) }
    if (targetClass != null) return@readPlease targetClass

    val clsType = type as? ClassType ?: return@readPlease null

    val location = clsType.allLineLocations().firstOrNull() ?: return@readPlease null

    debugProcess.positionManager.getSourcePosition(location)?.let { position ->
        position.elementAt?.let { SmartGotoImplementationExtension.tryFindContainingClass(it) }
    }
}

internal inline fun laterPlease(crossinline body: () -> Unit) = ApplicationManager.getApplication().invokeLater { body() }

internal inline fun <T> readPlease(crossinline body: () -> T) = ApplicationManager.getApplication().runReadAction<T> { body() } as T
