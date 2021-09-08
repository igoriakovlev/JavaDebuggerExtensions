package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.impl.XDebugSessionImpl
import org.jetbrains.plugins.emulatedStepOver.SessionService.Companion.runOnSession

class StartupActivity : StartupActivity {
    private class EmulatedStepOverSessionEventHandler(private val session: DebuggerSession) : XDebugSessionListener, EditorMouseListener {
        override fun sessionPaused(): Unit = with(session) { runOnSession { onStop() } }
        override fun sessionStopped(): Unit = Unit
        override fun sessionResumed(): Unit = Unit
    }

    override fun runActivity(project: Project) {
        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                val sessionHandler = EmulatedStepOverSessionEventHandler(session)
                xSession.addSessionListener(sessionHandler)
            }
        }
        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)
    }
}