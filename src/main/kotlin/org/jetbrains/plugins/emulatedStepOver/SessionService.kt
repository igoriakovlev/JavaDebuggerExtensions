package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.debugger.impl.DebuggerSession
import com.sun.jdi.Location
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.request.BreakpointRequest
import org.jetbrains.plugins.emulatedStepOver.InstrumentationMethodBreakpoint.Companion.instrumentationMethodBreakpoint
import org.jetbrains.plugins.runDebuggerCommand
import java.util.*

internal class SessionService {

    private val breakPoints = mutableListOf<InstrumentationMethodBreakpoint>()
    private val lineNodes = WeakHashMap<StackFrameProxy, Map<Long, LineControlFlowNode>>()

    private fun StackFrameProxy.getControlFlowReachableLocations(): List<Location>? {
        val nodes = lineNodes.getOrPut(this) {
            buildControlFlowNodes()
        }

        if (nodes.isEmpty()) return null

        fun getNodeByIndex(index: Long): LineControlFlowNode {
            var bestNode = nodes.entries.first()
            nodes.forEach {
                if (it.key > index && it.key < bestNode.key) {
                    bestNode = it
                }
            }
            return bestNode.value
        }

        fun getNodeByLine(line: Int): LineControlFlowNode? =
            nodes.values.firstOrNull { it.line == line }

        val location = location()
        val method = location.method()
        val lineNode = getNodeByLine(location.lineNumber()) ?: return null
        val result = mutableListOf<Location>()
        val visited = mutableSetOf<Int>()

        fun visitNode(node: LineControlFlowNode): Boolean {
            if (node.type == NodeType.EndedWithReturn) return false
            if (!visited.add(node.line)) return true

            if (node.type == NodeType.EndedWithLine) {
                method.locationsOfLine(node.nextLine).forEach {
                    result.add(it)
                }
            }

            node.jumpIndexes.forEach {
                if (!visitNode(getNodeByIndex(it))) return false
            }
            return true
        }

        return if (visitNode(lineNode)) result else null
    }

    internal fun DebuggerSession.onStop() {
        val events = process.suspendManager.pausedContext.eventSet ?: return
        val isEventFromOurRequest = events.filterIsInstance<BreakpointEvent>().any {
            val request = (it.request() as? BreakpointRequest)?.instrumentationMethodBreakpoint
            request != null && breakPoints.contains(request)
        }
        if (!isEventFromOurRequest) {
            process.deleteRequests()
        }
    }

    private fun DebugProcessImpl.deleteRequests() {
        breakPoints.forEach {
            requestsManager.deleteRequest(it)
        }
    }

    internal fun DebuggerSession.doStepOver(): Unit = runDebuggerCommand { suspendContext ->
        val process = suspendContext.debugProcess
        val thread = suspendContext.thread
        val currentFrame = thread?.frame(0)
        val locationsFromControlFlow = currentFrame?.getControlFlowReachableLocations()

        //Fallback
        if (locationsFromControlFlow == null) {
            val stepOver = process.createStepOverCommand(contextManager.context.suspendContext, false)
            process.managerThread.schedule(stepOver)
            return@runDebuggerCommand
        }

        locationsFromControlFlow.mapTo(breakPoints) {
            InstrumentationMethodBreakpoint(process, thread, it) {
                val isTargetFrame = thread.frame(0) == currentFrame
                if (isTargetFrame) {
                    process.deleteRequests()
                }
                isTargetFrame
            }
        }
        process.suspendManager.resume(process.suspendManager.pausedContext)
    }

    companion object {
        private val servicesCollection = WeakHashMap<DebuggerSession, SessionService>()

        inline fun DebuggerSession.runOnSession(body: SessionService.() -> Unit) =
            with(this) { getStepOverService(this).body() }

        fun getStepOverService(session: DebuggerSession): SessionService = synchronized(servicesCollection) {
            servicesCollection.getOrPut(session) {
                SessionService()
            }
        }
    }
}