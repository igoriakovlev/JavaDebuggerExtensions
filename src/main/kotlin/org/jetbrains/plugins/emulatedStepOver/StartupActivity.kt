package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.impl.XDebugSessionImpl
import org.jetbrains.plugins.isGradleRun

class StartupActivity : StartupActivity {
    private class EmulatedStepOverSessionEventHandler(private val sessionService: SessionService) : XDebugSessionListener, EditorMouseListener {
        override fun sessionPaused() = sessionService.onStop()
        override fun sessionStopped() = Unit
        override fun sessionResumed() = Unit
    }

    override fun runActivity(project: Project) {
        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                if (xSession.isGradleRun()) return
                val jumpService = SessionService.getStepOverService(session)
                val sessionHandler = EmulatedStepOverSessionEventHandler(jumpService)
                xSession.addSessionListener(sessionHandler)
            }
        }
        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)
    }
}