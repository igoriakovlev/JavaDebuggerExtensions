package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.debugger.jdi.MethodBytecodeUtil
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Opcodes

internal enum class NodeType {
    EndedWithLine,
    EndedWithReturn,
    EndedWithGoto
}

internal data class LineControlFlowNode(
    val line: Int,
    val nextLine: Int,
    val jumpIndexes: List<Long>,
    val type: NodeType
)

private inline fun LineControlFlowNode?.checkToBeEndedWithLineOrNull(body: () -> Unit) {
    require(this == null || type == NodeType.EndedWithLine)
    body()
}

internal fun StackFrameProxy.buildControlFlowNodes(): Map<Long, LineControlFlowNode> {

    val result = mutableMapOf<Long, LineControlFlowNode>()

    val labelToIndex = mutableMapOf<Label, Long>()
    val jumpLabels = mutableListOf<Label>()
    MethodBytecodeUtil.visit(location().method(), object : MethodVisitorWithCounter() {
        override fun visitLabel(label: Label?) {
            super.visitLabel(label)
            label?.let { labelToIndex[label] = instructionIndex }
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
    val location = location()
    val methodToVisit = location().method()
    val allowedLines = methodToVisit
        .allLineLocations("Java", location.sourceName())
        .map { it.lineNumber("Java") }
    MethodBytecodeUtil.visit(methodToVisit, object : MethodVisitorWithCounter() {
        var currentLine = -1
        var jumpIndexes = mutableListOf<Long>()

        override fun visitLineNumber(line: Int, start: Label) {
            super.visitLineNumber(line, start)
            if (line in allowedLines) {
                val beforeLineIndexes = result[instructionIndex]?.jumpIndexes ?: jumpIndexes
                result[instructionIndex] = LineControlFlowNode(currentLine, line, beforeLineIndexes, NodeType.EndedWithLine)
                jumpIndexes = mutableListOf()
                currentLine = line
            }
        }

        override fun visitJumpInsn(opcode: Int, label: Label?) {
            super.visitJumpInsn(opcode, label)
            label?.let { jumpIndexesIterator.next() }?.let { jumpIndexes.add(it) }
            when(opcode) {
                Opcodes.GOTO -> {
                    result[instructionIndex].checkToBeEndedWithLineOrNull {
                        result[instructionIndex] = LineControlFlowNode(currentLine, -1, jumpIndexes, NodeType.EndedWithGoto)
                    }
                    jumpIndexes = mutableListOf()
                }
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
                    result[instructionIndex].checkToBeEndedWithLineOrNull {
                        result[instructionIndex] = LineControlFlowNode(currentLine, -1, jumpIndexes, NodeType.EndedWithReturn)
                    }
                    jumpIndexes = mutableListOf()
                }
            }
        }

        override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
            super.visitLookupSwitchInsn(dflt, keys, labels)
            if (labels != null) {
                for (i in 0..labelToIndex.size) {
                    jumpIndexesIterator.next()?.let { jumpIndexes.add(it) }
                }
            }
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
            super.visitTableSwitchInsn(min, max, dflt, *labels)
            for (i in 0..labelToIndex.size) {
                if (labels[i] != null) {
                    jumpIndexesIterator.next()?.let { jumpIndexes.add(it) }
                }
            }
        }
    }, true)


    return result
}