package org.jetbrains.plugins.runToMultiLine

import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
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
import org.jetbrains.plugins.runToMultiLine.SessionService.Companion.runOnSession
import org.jetbrains.plugins.suspendedJavaSession
import java.awt.event.MouseEvent

class StartupActivity : StartupActivity {

    private class MouseListener(private val project: Project) : EditorMouseListener {
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
            val session = project.suspendedJavaSession ?: return

            val lineNumber = getLineNumber(e)
            if (lineNumber < 0) return

            with(session) { runOnSession { toggleBreakPoint(e.editor.document, lineNumber) } }
            e.consume()
        }
    }

    private class JumpToLineSessionEventHandler(private val session: DebuggerSession) : XDebugSessionListener {
        override fun sessionPaused(): Unit = with(session) { runOnSession { resetBreakPoints() } }
        override fun sessionStopped(): Unit = with(session) { runOnSession { resetBreakPoints() } }
        override fun sessionResumed(): Unit = Unit
    }

    override fun runActivity(project: Project) {
        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                val sessionHandler = JumpToLineSessionEventHandler(session)
                xSession.addSessionListener(sessionHandler)
                EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(MouseListener(project), project)
            }
        }
        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)
    }
}