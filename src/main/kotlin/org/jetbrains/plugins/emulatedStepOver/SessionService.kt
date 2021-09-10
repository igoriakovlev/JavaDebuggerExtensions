package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.debugger.impl.DebuggerSession
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.request.BreakpointRequest
import org.jetbrains.plugins.emulatedStepOver.InstrumentationMethodBreakpoint.Companion.instrumentationMethodBreakpoint
import org.jetbrains.plugins.runDebuggerCommand
import java.util.*

internal class SessionService {

    private val breakPoints = mutableListOf<InstrumentationMethodBreakpoint>()
    private val controlFlowNodes = WeakHashMap<StackFrameProxy, List<ControlFlowNode>>()

    private fun sortedLineArrays(method: Method, line: Int, controlFlowNodes: List<ControlFlowNode>): Pair<Array<Location>, Array<ControlFlowNode>>? {
        val lineNodes = controlFlowNodes.filter { it.lineNumber == line }
        if (lineNodes.isEmpty()) return null
        val locationsOfLine = method.locationsOfLine(line)
        if (lineNodes.size != locationsOfLine.size) return null

        if (lineNodes.size == 1) {
            return arrayOf(locationsOfLine[0]) to arrayOf(lineNodes[0])
        }

        //if arrays match we could find the line by position in sorted array
        val locationsArray = locationsOfLine.toTypedArray()
        locationsArray.sortBy { it.codeIndex() }

        val nodesArray = lineNodes.toTypedArray()
        nodesArray.sortBy { it.instructionIndex }

        return locationsArray to nodesArray
    }

    private fun mapLineNodeToLocation(method: Method, controlFlowNode: ControlFlowNode, controlFlowNodes: List<ControlFlowNode>): Location? {
        val line = controlFlowNode.lineNumber ?: return null
        val (locationsArray, nodesArray) = sortedLineArrays(method, line, controlFlowNodes) ?: return null
        if (locationsArray.size == 1) return locationsArray[0]
        val indexInArray = nodesArray.indexOf(controlFlowNode)
        if (indexInArray == -1) return null
        return locationsArray[indexInArray]
    }

    private fun mapLocationLineNode(method: Method, location: Location, controlFlowNodes: List<ControlFlowNode>): ControlFlowNode? {
        val line = location.lineNumber()
        val (locationsArray, nodesArray) = sortedLineArrays(method, line, controlFlowNodes) ?: return null
        if (nodesArray.size == 1) return nodesArray[0]
        val indexInArray = locationsArray.indexOf(location)
        if (indexInArray == -1) return null
        return nodesArray[indexInArray]
    }

    private fun StackFrameProxy.getControlFlowReachableLocations(): List<Location>? {
        val nodes = controlFlowNodes.getOrPut(this) {
            buildControlFlowNodes()
        }

        if (nodes.isEmpty()) return null //error -> fallback

        val location = location()
        val line = location.lineNumber()
        val method = location.method()

        val targetLineNode = mapLocationLineNode(method, location, nodes) ?: return null

        fun nodeNextByIndex(index: Long): ControlFlowNode? =
            nodes.filter { it.instructionIndex >= index }.minBy { it.instructionIndex }

        val afterLineIndex = if (targetLineNode.type == NodeType.SimpleLine) targetLineNode.instructionIndex + 1 else targetLineNode.instructionIndex
        val nodeAfterLine = nodeNextByIndex(afterLineIndex) ?: return null

        val result = mutableListOf<ControlFlowNode>()
        val visited = mutableSetOf<ControlFlowNode>()
        fun visitNodeAndCheckNoReturn(node: ControlFlowNode): Boolean {
            if (node.type == NodeType.Return) {
                if (node.lineNumber == null || node.lineNumber == line) return false
            }

            if (!visited.add(node)) return true

            if (node.lineNumber != null && node.lineNumber != line) {
                result.add(node)
                return true
            }

            when (node.type) {
                NodeType.SimpleLine, NodeType.Return -> return true
                NodeType.Goto, NodeType.ConditionalGoto -> check(node.jumpIndex > 0)
                else -> error("Invalid node")
            }

            val targetNode = nodeNextByIndex(node.jumpIndex) ?: return false
            val targetNodesAreOk = visitNodeAndCheckNoReturn(targetNode)
            if (node.type != NodeType.ConditionalGoto) return targetNodesAreOk

            val targetNodeAfterConditional = nodeNextByIndex(node.instructionIndex + 1) ?: return true
            return visitNodeAndCheckNoReturn(targetNodeAfterConditional)
        }

        if (!visitNodeAndCheckNoReturn(nodeAfterLine)) return null

        val locationResult = mutableListOf<Location>()
        for (currentNode in result) {
            val mappedLocation = mapLineNodeToLocation(method, currentNode, nodes) ?: return null
            locationResult.add(mappedLocation)
        }
        return locationResult
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