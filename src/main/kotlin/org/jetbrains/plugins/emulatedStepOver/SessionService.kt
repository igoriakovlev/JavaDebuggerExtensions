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
    private val controlFlowNodes = WeakHashMap<StackFrameProxy, List<ControlFlowNode>>()

    private fun StackFrameProxy.getControlFlowReachableLocations(): List<Location>? {
        val nodes = controlFlowNodes.getOrPut(this) {
            buildControlFlowNodes()
        }

        if (nodes.isEmpty()) return null //error -> fallback

        val location = location()
        val line = location.lineNumber()
        val method = location.method()

        val lineNodes = nodes.filter { it.lineNumber == line }
        if (lineNodes.isEmpty()) return null //error -> fallback

        val targetLineNodes: List<ControlFlowNode>
        if (lineNodes.size > 1) {
            val locationsOfLine = method.locationsOfLine(line)
            if (lineNodes.size != locationsOfLine.size) return null //error -> fallback

            //if arrays match we could find the line by position in sorted array
            val codeIndexesArray = locationsOfLine.toTypedArray()
            codeIndexesArray.sortBy { it.codeIndex() }

            val nodesArray = lineNodes.toTypedArray()
            nodesArray.sortBy { it.instructionIndex }

            val indexOfLocation = codeIndexesArray.indexOf(location)
            if (indexOfLocation < 0) return null //error -> fallback
            targetLineNodes = listOf(nodesArray[indexOfLocation])
        } else {
            targetLineNodes = lineNodes
        }

        fun nodesNextByIndex(index: Long): List<ControlFlowNode> {
            val bottomNodes = nodes.filter { it.instructionIndex >= index }
            val minIndex = bottomNodes.minBy { it.instructionIndex }?.instructionIndex ?: return emptyList()
            return bottomNodes.filter { it.instructionIndex == minIndex }
        }
        val nodesAfterLines = targetLineNodes.flatMap {
            val index = if (it.type == NodeType.SimpleLine) it.instructionIndex + 1 else it.instructionIndex
            nodesNextByIndex(index)
        }
        if (nodesAfterLines.isEmpty()) return null

        val result = mutableListOf<Location>()
        val visited = mutableSetOf<ControlFlowNode>()
        fun visitNodeAndCheckNoReturn(node: ControlFlowNode): Boolean {
            if (node.type == NodeType.Return) {
                if (node.lineNumber == null || node.lineNumber == line) return false
            }

            if (!visited.add(node)) return true

            if (node.lineNumber != null && node.lineNumber != line) {
                method.locationsOfLine(node.lineNumber).forEach {
                    result.add(it)
                }
                return true
            }

            when (node.type) {
                NodeType.SimpleLine, NodeType.Return -> return true
                NodeType.Goto, NodeType.ConditionalGoto -> check(node.jumpIndex > 0)
                else -> error("Invalid node")
            }

            val targetNodes = nodesNextByIndex(node.jumpIndex)
            if (targetNodes.isEmpty()) return false //error
            val targetNodesAreOk = targetNodes.all {
                visitNodeAndCheckNoReturn(it)
            }
            if (node.type != NodeType.ConditionalGoto) return targetNodesAreOk

            val targetNodesAfterConditional = nodesNextByIndex(node.instructionIndex + 1)
            return targetNodesAfterConditional.all {
                visitNodeAndCheckNoReturn(it)
            }
        }

        val controlPathIsNeverCallReturn = nodesAfterLines.all {
            visitNodeAndCheckNoReturn(it)
        }
        return if (controlPathIsNeverCallReturn) result else null //return or error path node found -> fallback
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