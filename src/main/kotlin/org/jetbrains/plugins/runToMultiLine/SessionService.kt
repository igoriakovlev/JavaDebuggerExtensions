package org.jetbrains.plugins.runToMultiLine

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.xdebugger.ui.DebuggerColors
import org.jetbrains.plugins.emulatedStepOver.InstrumentationMethodBreakpoint
import org.jetbrains.plugins.invokeIfNotSetAndSetFlag
import org.jetbrains.plugins.invokeLaterAndResetFlag
import org.jetbrains.plugins.runDebuggerCommand
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

internal class SessionService {
    private data class BreakpointInfo(
        val line: Int,
        val breakpoints: List<InstrumentationMethodBreakpoint>,
        val model: MarkupModel,
        val highlighter: RangeHighlighter
    )

    private val breakPoints = mutableListOf<BreakpointInfo>()
    private val inProgress = AtomicBoolean(false)

    private fun tryRemoveBreakpoint(session: DebuggerSession, line: Int): Boolean {
        val breakPoint = breakPoints.firstOrNull { it.line == line }
            ?: return false
        breakPoints.remove(breakPoint)

        session.runDebuggerCommand { suspendContext ->
            try {
                breakPoint.breakpoints.forEach {
                    suspendContext.debugProcess.requestsManager.deleteRequest(it)
                }
            } finally {
                inProgress.invokeLaterAndResetFlag {
                    breakPoint.model.removeHighlighter(breakPoint.highlighter)
                }
            }

        }
        return true
    }

    private fun setBreakPoint(session: DebuggerSession, document: Document, line: Int) {
        session.runDebuggerCommand { suspendContext ->
            val createdBreakpoints = mutableListOf<InstrumentationMethodBreakpoint>()
            try {
                val thread = suspendContext.debugProcess.debuggerContext.threadProxy
                    ?: return@runDebuggerCommand

                val currentLocation = thread.frame(0).location()
                val currentMethod = currentLocation.method()

                val targetLocations = currentMethod.locationsOfLine(line + 1)
                if (targetLocations.isEmpty()) return@runDebuggerCommand

                targetLocations.mapTo(createdBreakpoints) {
                    InstrumentationMethodBreakpoint(session.process, thread, it) { true }
                }
            } finally {
                inProgress.invokeLaterAndResetFlag {
                    if (createdBreakpoints.isNotEmpty()) {
                        val markupModel = DocumentMarkupModel.forDocument(document, session.project, true)
                        val highlighter = markupModel.addLineHighlighter(line,
                            DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER, markupAttribute)
                        breakPoints.add(BreakpointInfo(line, createdBreakpoints, markupModel, highlighter))
                    }
                }
            }
        }
    }

    fun DebuggerSession.resetBreakPoints() {
        inProgress.invokeIfNotSetAndSetFlag {
            runDebuggerCommand { suspendContext ->
                breakPoints.forEach { info ->
                    info.breakpoints.forEach { breakPoint ->
                        suspendContext.debugProcess.requestsManager.deleteRequest(breakPoint)
                    }
                }
                inProgress.invokeLaterAndResetFlag {
                    breakPoints.forEach { it.model.removeHighlighter(it.highlighter) }
                    breakPoints.clear()
                }
            }
        }
    }

    fun DebuggerSession.toggleBreakPoint(document: Document, line: Int) {
        inProgress.invokeIfNotSetAndSetFlag {
            if (!tryRemoveBreakpoint(this, line)) setBreakPoint(this, document, line)
        }
    }

    companion object {
        private val servicesCollection = WeakHashMap<DebuggerSession, SessionService>()
        val markupAttribute: TextAttributes =
            EditorColorsManager.getInstance().globalScheme.getAttributes(DebuggerColors.SMART_STEP_INTO_TARGET)

        inline fun DebuggerSession.runOnSession(body: SessionService.() -> Unit) =
            getJumpService(this).body()

        fun getJumpService(session: DebuggerSession): SessionService = synchronized(servicesCollection) {
            servicesCollection.getOrPut(session) { SessionService() }
        }
    }
}