package org.jetbrains.plugins

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.actions.DebuggerActionHandler
import java.util.concurrent.atomic.AtomicBoolean

internal object DebuggerUtils {
    internal fun <T> runInDebuggerThread(session: DebuggerSession, body: () -> T?): T? {
        var result: T? = null
        session.process.invokeInManagerThread {
            result = body()
        }
        return result
    }

    private fun <T : Any> DebugProcessImpl.invokeInManagerThread(f: (DebuggerContextImpl) -> T?): T? {
        var result: T? = null
        val command: DebuggerCommandImpl = object : DebuggerCommandImpl() {
            override fun action() {
                result = f(debuggerContext)
            }
        }

        when {
            DebuggerManagerThreadImpl.isManagerThread() ->
                managerThread.invoke(command)
            else ->
                managerThread.invokeAndWait(command)
        }

        return result
    }


    object DummyActionHandler : DebuggerActionHandler() {
        override fun perform(project: Project, event: AnActionEvent) = Unit

        override fun isEnabled(project: Project, event: AnActionEvent) = false

        override fun isHidden(project: Project, event: AnActionEvent?) = true
    }
}

internal fun DebuggerSession.runDebuggerCommand(action: (SuspendContextImpl) -> Unit) {
    val debuggerCommand = object : DebuggerContextCommandImpl(contextManager.context) {
        override fun threadAction(suspendContext: SuspendContextImpl) {
            action(suspendContext)
        }
    }
    process.managerThread.schedule(debuggerCommand)
}

internal inline fun AtomicBoolean.invokeLaterAndResetFlag(crossinline body: () -> Unit) {
    ApplicationManager.getApplication().invokeLater {
        invokeAndReset(body)
    }
}

internal inline fun AtomicBoolean.invokeAndReset(body: () -> Unit) {
    try {
        body()
    } finally {
        this.set(false)
    }
}

internal inline fun AtomicBoolean.invokeIfNotSetAndSetFlag(crossinline body: () -> Unit) {
    if (!this.compareAndSet(false, true)) return
    body()
}

internal val Project.suspendedJavaSession: DebuggerSession? get() {
    val xSession = XDebuggerManager.getInstance(this).currentSession ?: return null
    if (!xSession.isSuspended) return null
    return (xSession.debugProcess as? JavaDebugProcess)?.debuggerSession
}