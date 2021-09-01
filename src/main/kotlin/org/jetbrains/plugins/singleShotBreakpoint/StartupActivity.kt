package org.jetbrains.plugins.singleShotBreakpoint

import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.impl.XDebugSessionImpl
import org.jetbrains.plugins.isGradleRun
import java.awt.event.MouseEvent


class StartupActivity : StartupActivity, Disposable {
    private class JumpToLineSessionEventHandler(private val session: DebuggerSession, private val sessionService: SessionService) :
        XDebugSessionListener,
        EditorMouseListener {
        override fun sessionPaused() = sessionService.resetBreakPoints()
        override fun sessionStopped() {
            sessionService.resetBreakPoints()
            val eventMulticaster = EditorFactory.getInstance().eventMulticaster
            eventMulticaster.removeEditorMouseListener(this)
        }
        override fun sessionResumed() = Unit

        private fun getLineNumber(event: EditorMouseEvent): Int {
            val editor = event.editor
            val line = editor.yToVisualLine(event.mouseEvent.y)
            if (line >= (editor as EditorImpl).visibleLineCount) {
                return -1
            }
            val offset = editor.visualLineStartOffset(line)
            val lineStartOffset = EditorUtil.getNotFoldedLineStartOffset(editor, offset)
            return editor.document.getLineNumber(lineStartOffset)
        }

        override fun mousePressed(e: EditorMouseEvent) {
            if (e.mouseEvent.button != MouseEvent.BUTTON2) return
            if (e.area != EditorMouseEventArea.LINE_NUMBERS_AREA) return
            if (session.xDebugSession?.isSuspended != true) return

            val lineNumber = getLineNumber(e)
            if (lineNumber < 0) return

            sessionService.toggleBreakPoint(e.editor.document, lineNumber)
            e.consume()
        }

    }

    override fun runActivity(project: Project) {
        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                if (xSession.isGradleRun()) return

                val jumpService = SessionService.getJumpService(session)
                val sessionHandler = JumpToLineSessionEventHandler(session, jumpService)
                xSession.addSessionListener(sessionHandler)
                val eventMulticaster = EditorFactory.getInstance().eventMulticaster
                eventMulticaster.addEditorMouseListener(sessionHandler, this@StartupActivity)
            }
        }
        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)
    }

    override fun dispose() {

    }
}