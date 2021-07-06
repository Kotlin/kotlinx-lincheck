/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.printInColumnsCustom
import java.util.*
import kotlin.math.min

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun StringBuilder.appendTrace(
    scenario: ExecutionScenario,
    results: ExecutionResult?,
    trace: Trace
) {
    val startTraceGraphNode = constructTraceGraph(scenario, results, trace)
    val traceRepresentation = traceGraphToRepresentationList(startTraceGraphNode)
    val traceRepresentationSplitted = splitToColumns(scenario.threads, traceRepresentation)
    appendln("Parallel part trace:")
    append(printInColumnsCustom(traceRepresentationSplitted) {
        StringBuilder().apply {
            for (i in it.indices) {
                append(if (i == 0) "| " else " | ")
                append(it[i])
            }
            append(" |")
        }.toString()
    })
    objectNumeration.clear() // clear the numeration at the end to avoid memory leaks
}

/**
 * Convert trace events to the final form of a matrix of strings.
 */
private fun splitToColumns(nThreads: Int, traceRepresentation: List<TraceEventRepresentation>): List<List<String>> {
    val result = List(nThreads) { mutableListOf<String>() }
    for (event in traceRepresentation) {
        val columnId = event.iThread
        // write message in an appropriate column
        result[columnId].add(event.representation)
        val neededSize = result[columnId].size
        for (column in result)
            if (column.size != neededSize)
                column.add("")
    }
    return result
}

/**
 * Constructs a trace graph based on the provided [trace].
 * Returns the node corresponding to the starting trace event.
 *
 * A trace graph consists of two types of edges:
 * `next` edges form a single-directed list in which the order of events is the same as in [trace].
 * `internalEvents` edges form a directed forest.
 */
private fun constructTraceGraph(scenario: ExecutionScenario, results: ExecutionResult?, trace: Trace): TraceNode? {
    val tracePoints = trace.trace
    // last events that were executed for each thread. It is either thread finish events or events before crash
    val lastExecutedEvents = IntArray(scenario.threads) { iThread ->
        tracePoints.mapIndexed { i, e -> Pair(i, e) }.lastOrNull { it.second.iThread == iThread }?.first ?: -1
    }
    // last actor that was handled for each thread
    val lastHandledActor = IntArray(scenario.threads) { -1 }
    // actor nodes for each actor in each thread
    val actorNodes = Array(scenario.threads) { Array<ActorNode?>(scenario.parallelExecution[it].size) { null } }
    // call nodes for each method call
    val callNodes = mutableMapOf<Int, CallNode>()
    // all trace nodes in order corresponding to `tracePoints`
    val traceGraphNodes = mutableListOf<TraceNode>()

    for (eventId in tracePoints.indices) {
        val event = tracePoints[eventId]
        val iThread = event.iThread
        val actorId = event.actorId
        // add all actors that started since the last event
        while (lastHandledActor[iThread] < min(actorId, scenario.parallelExecution[iThread].lastIndex)) {
            val nextActor = ++lastHandledActor[iThread]
            // create new actor node actor
            val actorNode = traceGraphNodes.createAndAppend { lastNode ->
                ActorNode(iThread, lastNode, trace.verboseTrace, 0, scenario.parallelExecution[iThread][nextActor], results[iThread, nextActor])
            }
            actorNodes[iThread][nextActor] = actorNode
            traceGraphNodes.add(actorNode)
        }
        // add the event
        when (event) {
            // simpler code for FinishEvent, because it does not have actorId or callStackTrace
            is FinishThreadTracePoint -> traceGraphNodes.createAndAppend { lastNode ->
                TraceLeafEvent(iThread, lastNode, trace.verboseTrace, 1, event)
            }
            else -> {
                var innerNode: TraceInnerNode = actorNodes[iThread][actorId]!!
                for (call in event.callStackTrace) {
                    val callId = call.identifier
                    // Switch events that happen as a first event of the method are lifted out of the method in the trace
                    if (!callNodes.containsKey(callId) && event is SwitchEventTracePoint) break
                    val callNode = callNodes.computeIfAbsent(callId) {
                        // create a new call node if needed
                        val result = traceGraphNodes.createAndAppend { lastNode ->
                            CallNode(iThread, lastNode, trace.verboseTrace, innerNode.callDepth + 1, call.call)
                        }
                        // make it a child of the previous node
                        innerNode.addInternalEvent(result)
                        result
                    }
                    innerNode = callNode
                }
                val isLastExecutedEvent = eventId == lastExecutedEvents[iThread]
                val node = traceGraphNodes.createAndAppend { lastNode ->
                    TraceLeafEvent(iThread, lastNode, trace.verboseTrace, innerNode.callDepth + 1, event, isLastExecutedEvent)
                }
                innerNode.addInternalEvent(node)
            }
        }
    }
    // add an ActorResultNode to each actor, because did not know where actor ends before
    for (iThread in actorNodes.indices)
        for (actorId in actorNodes[iThread].indices) {
            actorNodes[iThread][actorId]?.let {
                // insert an ActorResultNode between the last actor event and the next event after it
                val lastEvent = it.lastInternalEvent
                val lastEventNext = lastEvent.next
                val resultNode = ActorResultNode(iThread, lastEvent, trace.verboseTrace, it.callDepth + 1, results[iThread, actorId])
                it.addInternalEvent(resultNode)
                resultNode.next = lastEventNext
            }
        }
    return traceGraphNodes.firstOrNull()
}

private operator fun ExecutionResult?.get(iThread: Int, actorId: Int): Result? =
    if (this == null) null else this.parallelResults[iThread][actorId]

/**
 * Create a new trace node and add it to the end of the list.
 */
private fun <T : TraceNode> MutableList<TraceNode>.createAndAppend(constructor: (lastNode: TraceNode?) -> T): T =
    constructor(lastOrNull()).also { add(it) }

private fun traceGraphToRepresentationList(startNode: TraceNode?): List<TraceEventRepresentation> {
    var curNode: TraceNode? = startNode
    val traceRepresentation = mutableListOf<TraceEventRepresentation>()
    while (curNode != null) {
        curNode = curNode.addRepresentationTo(traceRepresentation)
    }
    return traceRepresentation
}

private sealed class TraceNode(
    protected val iThread: Int,
    last: TraceNode?,
    val verboseTrace: Boolean,
    val callDepth: Int // for tree indentation
) {
    // `next` edges form an ordered single-directed event list
    var next: TraceNode? = null
    // `lastInternalEvent` helps to skip internal events if an actor or a method call can be compressed
    abstract val lastInternalEvent: TraceNode
    // `lastState` helps to find the last state needed for the compression
    abstract val lastState: String?
    // whether the internal events should be reported
    abstract val shouldBeExpanded: Boolean

    init {
        last?.let {
            it.next = this
        }
    }

    /**
     * Adds this node representation to the [traceRepresentation] and returns the next node to be processed.
     */
    abstract fun addRepresentationTo(traceRepresentation: MutableList<TraceEventRepresentation>): TraceNode?
}

private class TraceLeafEvent(
    iThread: Int,
    last: TraceNode?,
    verboseTrace: Boolean,
    callDepth: Int,
    private val event: TracePoint,
    lastExecutedEvent: Boolean = false
) : TraceNode(iThread, last, verboseTrace, callDepth) {
    override val lastState: String? = if (event is StateRepresentationTracePoint) event.stateRepresentation else null
    override val lastInternalEvent: TraceNode = this
    override val shouldBeExpanded: Boolean = lastExecutedEvent || event is SwitchEventTracePoint || verboseTrace || event is CrashTracePoint

    override fun addRepresentationTo(traceRepresentation: MutableList<TraceEventRepresentation>): TraceNode? {
        val representation = traceIndentation() + event.toString()
        traceRepresentation.add(TraceEventRepresentation(iThread, representation))
        return next
    }
}

private abstract class TraceInnerNode(iThread: Int, last: TraceNode?, verboseTrace: Boolean, callDepth: Int)
    : TraceNode(iThread, last, verboseTrace,callDepth) {
    override val lastState: String?
        get() = internalEvents.map { it.lastState }.lastOrNull { it != null }
    override val lastInternalEvent: TraceNode
        get() = if (internalEvents.isEmpty()) this else internalEvents.last().lastInternalEvent
    override val shouldBeExpanded: Boolean
        by lazy { internalEvents.any { it.shouldBeExpanded } }
    private val internalEvents = mutableListOf<TraceNode>()

    fun addInternalEvent(node: TraceNode) {
        internalEvents.add(node)
    }
}

private class CallNode(iThread: Int, last: TraceNode?, verboseTrace: Boolean, callDepth: Int, private val call: MethodCallTracePoint)
    : TraceInnerNode(iThread, last, verboseTrace, callDepth) {
    // suspended method contents should be reported
    override val shouldBeExpanded: Boolean by lazy { call.wasSuspended || super.shouldBeExpanded }

    override fun addRepresentationTo(traceRepresentation: MutableList<TraceEventRepresentation>): TraceNode? =
        if (!shouldBeExpanded) {
            traceRepresentation.add(TraceEventRepresentation(iThread, traceIndentation() + "$call"))
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            traceRepresentation.add(TraceEventRepresentation(iThread, traceIndentation() + "$call"))
            next
        }
}

private class ActorNode(iThread: Int, last: TraceNode?, verboseTrace: Boolean, callDepth: Int, private val actor: Actor, private val result: Result?)
    : TraceInnerNode(iThread, last, verboseTrace, callDepth) {
    override fun addRepresentationTo(traceRepresentation: MutableList<TraceEventRepresentation>): TraceNode? =
        if (!shouldBeExpanded) {
            val representation = "$actor" + if (result != null) ": $result" else ""
            traceRepresentation.add(TraceEventRepresentation(iThread, representation))
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            traceRepresentation.add(TraceEventRepresentation(iThread, "$actor"))
            next
        }
}

private class ActorResultNode(iThread: Int, last: TraceNode?, verboseTrace: Boolean, callDepth: Int, private val result: Result?)
    : TraceNode(iThread, last, verboseTrace, callDepth) {
    override val lastState: String? = null
    override val lastInternalEvent: TraceNode = this
    override val shouldBeExpanded: Boolean = false

    override fun addRepresentationTo(traceRepresentation: MutableList<TraceEventRepresentation>): TraceNode? {
        if (result != null)
            traceRepresentation.add(TraceEventRepresentation(iThread, traceIndentation() + "result: $result"))
        return next
    }
}

private const val TRACE_INDENTATION = "  "

private fun TraceNode.traceIndentation() = TRACE_INDENTATION.repeat(callDepth)

private fun TraceNode.stateEventRepresentation(iThread: Int, stateRepresentation: String) =
    TraceEventRepresentation(iThread, traceIndentation() + "STATE: $stateRepresentation")

private class TraceEventRepresentation(val iThread: Int, val representation: String)

// Should be called only during `appendTrace` invocation
internal fun getObjectNumber(clazz: Class<Any>, obj: Any): Int = objectNumeration
    .computeIfAbsent(clazz) { WeakHashMap() }
    .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size }

private val objectNumeration = WeakHashMap<Class<Any>, MutableMap<Any, Int>>()