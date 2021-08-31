package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.ui.JavaDebuggerSupport
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.DebuggerSupport
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.actions.DebuggerActionHandler
import com.intellij.xdebugger.impl.actions.XDebuggerActionBase
import com.intellij.xdebugger.impl.actions.XDebuggerSuspendedActionHandler


class EmulatedStepOverActionHandler : XDebuggerSuspendedActionHandler() {
    override fun isEnabled(session: XDebugSession, dataContext: DataContext): Boolean {
        if (!isEnabled(session) || !super.isEnabled(session, dataContext))
            return false
        val xDebugSession = session as? XDebugSessionImpl ?: return false
        if (xDebugSession.debugProcess !is JavaDebugProcess) return false
        return true
    }

    override fun perform(session: XDebugSession, dataContext: DataContext) {
        val xDebugSession = session as? XDebugSessionImpl ?: return
        val debugProcess = xDebugSession.debugProcess as? JavaDebugProcess ?: return
        doStepOver(debugProcess.debuggerSession)
    }
}

private class DummyActionHandler : DebuggerActionHandler() {

    companion object {
        val INSTANCE = DummyActionHandler()
    }

    override fun perform(project: Project, event: AnActionEvent) = Unit

    override fun isEnabled(project: Project, event: AnActionEvent) = false

    override fun isHidden(project: Project, event: AnActionEvent?) = true
}

class EmulatedStepOverAction : XDebuggerActionBase(true) {

    override fun isEnabled(e: AnActionEvent?): Boolean {
        val project = e?.project ?: return false
        return XDebuggerManager.getInstance(project).currentSession?.isSuspended ?: false
    }

    private val handler = EmulatedStepOverActionHandler()

    override fun getHandler(debuggerSupport: DebuggerSupport): DebuggerActionHandler =
        if (debuggerSupport is JavaDebuggerSupport) handler else DummyActionHandler.INSTANCE
}