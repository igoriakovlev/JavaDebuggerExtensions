package org.jetbrains.plugins.runToMultiLine

import com.intellij.debugger.ui.JavaDebuggerSupport
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.DebuggerSupport
import com.intellij.xdebugger.impl.actions.DebuggerActionHandler
import com.intellij.xdebugger.impl.actions.XDebuggerActionBase
import org.jetbrains.plugins.DebuggerUtils

class RunToMultiLineAction : XDebuggerActionBase(true) {
    override fun isEnabled(e: AnActionEvent?): Boolean {
        val project = e?.project ?: return false
        return XDebuggerManager.getInstance(project).currentSession?.isSuspended ?: false
    }

    private val handler = RunToMultiLineHandler()

    override fun getHandler(debuggerSupport: DebuggerSupport): DebuggerActionHandler =
        if (debuggerSupport is JavaDebuggerSupport) handler else DebuggerUtils.DummyActionHandler
}