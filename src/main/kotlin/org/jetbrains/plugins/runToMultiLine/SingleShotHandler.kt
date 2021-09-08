package org.jetbrains.plugins.runToMultiLine

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import com.intellij.xdebugger.impl.actions.XDebuggerSuspendedActionHandler
import org.jetbrains.plugins.runToMultiLine.SessionService.Companion.runOnSession

internal class RunToMultiLineHandler : XDebuggerSuspendedActionHandler() {

    override fun isEnabled(session: XDebugSession, dataContext: DataContext): Boolean {
        if (!isEnabled(session) || !super.isEnabled(session, dataContext))
            return false

        val xDebugSession = session as? XDebugSessionImpl ?: return false
        if (xDebugSession.debugProcess !is JavaDebugProcess) return false

        val position = XDebuggerUtilImpl.getCaretPosition(session.project, dataContext)

        val currentLine = session.currentPosition?.line ?: return false
        val positionLine = position?.line ?: return false

        if (currentLine == positionLine) return false

        return true
    }

    override fun perform(session: XDebugSession, dataContext: DataContext) {
        fun balloonError(errorText: String) {
            ToolWindowManager.getInstance(session.project).notifyByBalloon(
                ToolWindowId.DEBUG,
                MessageType.ERROR,
                errorText
            )
        }
        fun balloonDefaultError() = balloonError("File to set single-shot breakpoint.")

        val xDebugSession = session as? XDebugSessionImpl ?: return balloonDefaultError()
        val debugProcess = xDebugSession.debugProcess as? JavaDebugProcess ?: return balloonDefaultError()

        val project = xDebugSession.project
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?:
            FileEditorManager.getInstance(project).selectedTextEditor ?:
            return balloonDefaultError()

        val offset: Int = editor.caretModel.offset
        val positionLine = editor.document.getLineNumber(offset)

        val currentLine = session.currentPosition?.line ?: return balloonDefaultError()

        if (currentLine == positionLine) return balloonDefaultError()

        with(debugProcess.debuggerSession) {
            runOnSession { toggleBreakPoint(editor.document, positionLine) }
        }
    }
}