package org.jetbrains.plugins.emulatedStepOver

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.requests.Requestor
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.breakpoints.SyntheticLineBreakpoint
import com.intellij.openapi.editor.Document
import com.sun.jdi.Location
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.request.BreakpointRequest

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

    companion object {
        private val REQUEST_KEY = com.intellij.openapi.util.Key.create<Requestor>("InstrumentationMethodBreakpoint")
        val BreakpointRequest.instrumentationMethodBreakpoint: InstrumentationMethodBreakpoint? get() = getProperty(REQUEST_KEY) as? InstrumentationMethodBreakpoint
    }

    override fun createRequest(debugProcess: DebugProcessImpl) {
        debugProcess.requestsManager.run {
            enableRequest(createBreakpointRequest(this@InstrumentationMethodBreakpoint, location).also {
                filterThread = thread.threadReference
                it.putProperty(REQUEST_KEY, this@InstrumentationMethodBreakpoint)
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