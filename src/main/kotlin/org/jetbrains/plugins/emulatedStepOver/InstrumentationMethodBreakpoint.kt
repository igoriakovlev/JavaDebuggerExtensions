package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.breakpoints.SyntheticLineBreakpoint
import com.intellij.openapi.editor.Document
import com.sun.jdi.Location
import com.sun.jdi.event.LocatableEvent

internal class InstrumentationMethodBreakpoint(
    private val process: DebugProcessImpl,
    private val thread: ThreadReferenceProxyImpl,
    private val location: Location,
    private val action: () -> Boolean
) : SyntheticLineBreakpoint(process.project) {

    init {
        suspendPolicy = DebuggerSettings.SUSPEND_ALL
        createRequest(process)
    }

    override fun createRequest(debugProcess: DebugProcessImpl) {
        debugProcess.requestsManager.run {
            enableRequest(createBreakpointRequest(this@InstrumentationMethodBreakpoint, location).also {
                filterThread = thread.threadReference
            })
        }
    }

    override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent): Boolean {
        return action()
    }

    override fun getLineIndex(): Int = - 1
    override fun getFileName(): String = ""
    override fun getDocument(): Document? = null
}