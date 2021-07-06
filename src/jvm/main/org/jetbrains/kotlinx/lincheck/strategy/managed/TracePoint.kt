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
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.nvm.CrashError
import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*
import java.math.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

data class Trace(val trace: List<TracePoint>, val verboseTrace: Boolean)

/**
 * Essentially, a trace is a list of trace points, which represent
 * interleaving events, such as code location passing or thread switches,
 * and store additional information that makes the trace more readable,
 * such as the current state representations.
 *
 * [callStackTrace] helps to understand whether two events
 * happened in the same, nested, or disjoint methods.
 */
sealed class TracePoint(val iThread: Int, val actorId: Int, internal val callStackTrace: CallStackTrace) {
    protected abstract fun toStringImpl(): String
    override fun toString(): String = toStringImpl()
}

internal typealias CallStackTrace = List<CallStackTraceElement>
internal typealias TracePointConstructor = (iThread: Int, actorId: Int, CallStackTrace) -> TracePoint
internal typealias CodeLocationTracePointConstructor = (iThread: Int, actorId: Int, CallStackTrace, StackTraceElement) -> TracePoint

internal class SwitchEventTracePoint(
    iThread: Int, actorId: Int,
    val reason: SwitchReason,
    callStackTrace: CallStackTrace
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(): String {
        val reason = reason.toString()
        return "switch" + if (reason.isEmpty()) "" else " (reason: $reason)"
    }
}

/**
 * While code locations just define certain bytecode instructions,
 * code location trace points correspond to visits of these bytecode instructions.
 * [stackTraceElement] provides information about the class, the file and the position in file
 * the code location has.
 */
internal abstract class CodeLocationTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    protected val stackTraceElement: StackTraceElement
) : TracePoint(iThread, actorId, callStackTrace)

internal class StateRepresentationTracePoint(
    iThread: Int, actorId: Int,
    val stateRepresentation: String,
    callStackTrace: CallStackTrace
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(): String = "STATE: $stateRepresentation"
}

internal class FinishThreadTracePoint(iThread: Int) : TracePoint(iThread, Int.MAX_VALUE, emptyList()) {
    override fun toStringImpl(): String = "thread is finished"
}

internal class ReadTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String?,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var value: Any? = null

    override fun toStringImpl(): String = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("READ")
        append(": ${adornedStringRepresentation(value)}")
        append(" at ${stackTraceElement.shorten()}")
    }.toString()

    fun initializeReadValue(value: Any?) {
        this.value = value
    }
}

internal class WriteTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String?,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var value: Any? = null

    override fun toStringImpl(): String  = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("WRITE(")
        append(adornedStringRepresentation(value))
        append(") at ${stackTraceElement.shorten()}")
    }.toString()

    fun initializeWrittenValue(value: Any?) {
        this.value = value
    }
}

internal class MethodCallTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val methodName: String,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var returnedValue: Any? = NO_VALUE
    private var thrownException: Throwable? = null
    private var parameters: Array<Any?>? = null
    private var ownerName: String? = null

    val wasSuspended get() = returnedValue === COROUTINE_SUSPENDED

    override fun toStringImpl(): String = StringBuilder().apply {
        if (ownerName != null)
            append("$ownerName.")
        append("$methodName(")
        if (parameters != null)
            append(parameters!!.joinToString(",", transform = ::adornedStringRepresentation))
        append(")")
        if (returnedValue != NO_VALUE)
            append(": ${adornedStringRepresentation(returnedValue)}")
        else if (thrownException != null &&
            thrownException != ForcibleExecutionFinishException &&
            thrownException !is CrashError
        )
            append(": threw ${thrownException!!.javaClass.simpleName}")
        append(" at ${stackTraceElement.shorten()}")
    }.toString()

    fun initializeReturnedValue(value: Any?) {
        this.returnedValue = value
    }

    fun initializeThrownException(exception: Throwable) {
        this.thrownException = exception
    }

    fun initializeParameters(parameters: Array<Any?>) {
        this.parameters = parameters
    }

    fun initializeOwnerName(ownerName: String?) {
        this.ownerName = ownerName
    }
}
private val NO_VALUE = Any()

internal class MonitorEnterTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringImpl(): String = "MONITORENTER at " + stackTraceElement.shorten()
}

internal class MonitorExitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringImpl(): String = "MONITOREXIT at " + stackTraceElement.shorten()
}

internal class WaitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringImpl(): String = "WAIT at " + stackTraceElement.shorten()
}

internal class NotifyTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringImpl(): String = "NOTIFY at " + stackTraceElement.shorten()
}

internal class ParkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringImpl(): String = "PARK at " + stackTraceElement.shorten()
}

internal class UnparkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringImpl(): String = "UNPARK at " + stackTraceElement.shorten()
}

internal class CoroutineCancellationTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
) : TracePoint(iThread, actorId, callStackTrace) {
    private lateinit var cancellationResult: CancellationResult
    private var exception: Throwable? = null

    fun initializeCancellationResult(cancellationResult: CancellationResult) {
        this.cancellationResult = cancellationResult
    }

    fun initializeException(e: Throwable) {
        this.exception = e;
    }

    override fun toStringImpl(): String {
        if (exception != null) return "EXCEPTION WHILE CANCELLATION"
        return when (cancellationResult) {
            CANCELLED_BEFORE_RESUMPTION -> "CANCELLED BEFORE RESUMPTION"
            CANCELLED_AFTER_RESUMPTION -> "PROMPT CANCELLED AFTER RESUMPTION"
            CANCELLATION_FAILED -> "CANCELLATION ATTEMPT FAILED"
        }
    }
}

internal class CrashTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val reason: CrashReason
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(): String = reason.toString()
}

internal enum class CrashReason(private val reason: String) {
    CRASH("CRASH"),
    SYSTEM_CRASH("SYSTEM CRASH");
    override fun toString() = reason
}

/**
 * Removes package info in the stack trace element representation.
 */
private fun StackTraceElement.shorten(): String {
    val stackTraceElement = this.toString()
    for (i in stackTraceElement.indices.reversed())
        if (stackTraceElement[i] == '/')
            return stackTraceElement.substring(i + 1 until stackTraceElement.length)
    return stackTraceElement
}

private fun adornedStringRepresentation(any: Any?): String {
    // Primitive types (and several others) are immutable and
    // have trivial `toString` implementation, which is used here.
    if (any == null || any.javaClass.isImmutableWithNiceToString)
        return any.toString()
    // simplified representation for Continuations
    // (we usually do not really care about details).
    if (any is Continuation<*>)
        return "<cont>"
    // Instead of java.util.HashMap$Node@3e2a56 show Node@1.
    // It is better not to use `toString` in general since
    // we usually care about references to certain objects,
    // not about the content inside them.
    val id = getObjectNumber(any.javaClass, any)
    return "${any.javaClass.simpleName}@$id"
}

internal enum class SwitchReason(private val reason: String) {
    MONITOR_WAIT("wait on monitor"),
    LOCK_WAIT("lock is already acquired"),
    ACTIVE_LOCK("active lock detected"),
    SUSPENDED("coroutine is suspended"),
    STRATEGY_SWITCH("");

    override fun toString() = reason
}

/**
 * Method call info.
 *
 * All methods calls are enumerated to make it possible to distinguish different calls of the same method.
 * Suspended method calls have the same [identifier] before and after suspension, but different [call] points.
 */
internal class CallStackTraceElement(val call: MethodCallTracePoint, val identifier: Int)

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val Class<out Any>?.isImmutableWithNiceToString get() = this?.canonicalName in
    listOf(
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Short::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Character::class.java,
        java.lang.Byte::class.java,
        java.lang.Boolean::class.java,
        java.lang.String::class.java,
        BigInteger::class.java,
        BigDecimal::class.java,
        kotlinx.coroutines.internal.Symbol::class.java,
    ).map { it.canonicalName } +
    listOf(
        REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.Collections.SingletonList",
        REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.Collections.SingletonMap",
        REMAPPED_PACKAGE_CANONICAL_NAME + "java.util.Collections.SingletonSet"
    )