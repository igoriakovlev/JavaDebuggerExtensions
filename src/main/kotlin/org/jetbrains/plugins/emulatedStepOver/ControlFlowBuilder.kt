package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.debugger.jdi.MethodBytecodeUtil
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Opcodes

internal enum class NodeType {
    ConditionalGoto,
    Goto,
    Return,
    SimpleLine,
}

internal data class ControlFlowNode(
    val lineNumber: Int?,
    val instructionIndex: Long,
    val jumpIndex: Long,
    val type: NodeType
)

internal fun StackFrameProxy.buildControlFlowNodes(): List<ControlFlowNode> {

    val nodes = mutableListOf<ControlFlowNode>()
    val lines = mutableMapOf<Long, Int>()

    val labelToIndex = mutableMapOf<Label, Long>()
    val jumpLabels = mutableListOf<Label>()

    val methodToVisit = location().method()

    val location = location()
    val allowedLines = methodToVisit
        .allLineLocations("Java", location.sourceName())
        .map { it.lineNumber("Java") }

    MethodBytecodeUtil.visit(methodToVisit, object : MethodVisitorWithCounter() {

        override fun visitLineNumber(line: Int, start: Label?) {
            super.visitLineNumber(line, start)
            if (line in allowedLines) {
                lines[instructionIndex + 1 ] = line
            }
        }

        override fun visitLabel(label: Label?) {
            super.visitLabel(label)
            label?.let { labelToIndex[label] = instructionIndex + 1 }
        }

        override fun visitJumpInsn(opcode: Int, label: Label?) {
            super.visitJumpInsn(opcode, label)
            label?.let { jumpLabels.add(label) }
        }

        override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
            super.visitLookupSwitchInsn(dflt, keys, labels)
            labels?.let { jumpLabels.addAll(it) }
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
            super.visitTableSwitchInsn(min, max, dflt, *labels)
            labels.forEach { label -> label?.let { jumpLabels.add(it) } }
        }
    }, true)

    val jumpIndexesIterator = jumpLabels.asSequence().map { labelToIndex[it] }.iterator()
    MethodBytecodeUtil.visit(methodToVisit, object : MethodVisitorWithCounter() {

        override fun visitJumpInsn(opcode: Int, label: Label?) {
            super.visitJumpInsn(opcode, label)
            val jumpIndex = label?.let { jumpIndexesIterator.next() }
            if (jumpIndex != null) {
                val nodeType = if (opcode == Opcodes.GOTO) NodeType.Goto else NodeType.ConditionalGoto
                nodes.add(ControlFlowNode(lines[instructionIndex], instructionIndex, jumpIndex, nodeType))
                lines.remove(instructionIndex)
            }
        }

        override fun visitInsn(opcode: Int) {
            super.visitInsn(opcode)
            when (opcode) {
                Opcodes.RETURN,
                Opcodes.IRETURN,
                Opcodes.FRETURN,
                Opcodes.ARETURN,
                Opcodes.LRETURN,
                Opcodes.DRETURN,
                Opcodes.ATHROW -> {
                    nodes.add(ControlFlowNode(lines[instructionIndex], instructionIndex, -1, NodeType.Return))
                    lines.remove(instructionIndex)
                }
            }
        }

        override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
            super.visitLookupSwitchInsn(dflt, keys, labels)
            if (labels != null) {
                val lineIfAny = lines[instructionIndex]
                lines.remove(instructionIndex)
                for (i in 0..labelToIndex.size) {
                    val index = jumpIndexesIterator.next()
                    if (index != null) {
                        nodes.add(ControlFlowNode(lineIfAny, instructionIndex, index, NodeType.ConditionalGoto))
                    }
                }
            }
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
            super.visitTableSwitchInsn(min, max, dflt, *labels)
            val lineIfAny = lines[instructionIndex]
            lines.remove(instructionIndex)
            for (i in 0..labelToIndex.size) {
                if (labels[i] != null) {
                    val index = jumpIndexesIterator.next()
                    if (index != null) {
                        nodes.add(ControlFlowNode(lineIfAny, instructionIndex, index, NodeType.ConditionalGoto))
                    }
                }
            }
        }
    }, true)

    for (line in lines) {
        nodes.add(ControlFlowNode(line.value, line.key, -1, NodeType.SimpleLine))
    }

    return nodes
}