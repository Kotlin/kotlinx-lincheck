package org.jetbrains.kotlinx.lincheck

import java.io.Serializable
import kotlin.coroutines.*

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

/**
 * The instance of this class represents a result of actor invocation.
 *
 * <p> If the actor invocation suspended the thread and did not get the final result yet
 * though it can be resumed later, then the {@link Type#NO_RESULT no_result result type} is used.
 *
 * [wasSuspended] is true if before getting this result the actor invocation suspended the thread.
 * If result is [NoResult] and [wasSuspended] is true it means that
 * the execution thread was suspended without any chance to be resumed,
 * meaning that all other execution threads completed their execution or were suspended too.
 */
sealed class Result {
    abstract val wasSuspended: Boolean
    protected val wasSuspendedPrefix: String get() = (if (wasSuspended) "SUSPENDED + " else "")
}

/**
 * Type of result used if the actor invocation returns any value.
 */
class ValueResult @JvmOverloads constructor(val value: Any?, override val wasSuspended: Boolean = false) : Result() {
    private val valueClassTransformed: Boolean get() = value?.javaClass?.classLoader is TransformationClassLoader
    private val serializedObject: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        check(value is Serializable?) {
            "The result should either be a type always loaded by the system class loader " +
                    "(e.g., Int, String, List<T>) or implement Serializable interface; " +
                    "the actual class is ${value?.javaClass}."
        }
        if (!valueClassTransformed) {
            // The object is not transformed
            value.serialize()
        } else {
            // The object is not transformed and should be converted beforehand
            value.convertForLoader(LinChecker::class.java.classLoader).serialize()
        }
    }

    override fun toString() = wasSuspendedPrefix + "$value"

    override fun equals(other: Any?): Boolean {
        // Check that the classes are equal by names
        // since they can be loaded via different class loaders.
        if (javaClass.name != other?.javaClass?.name) return false
        other as ValueResult
        // Is `wasSuspended` flag the same?
        if (wasSuspended != other.wasSuspended) return false
        // When both value are not transformed, then compare them directly, otherwise serialize to compare
        return if (!valueClassTransformed && !other.valueClassTransformed) value == other.value
        else serializedObject.contentEquals(other.serializedObject)
    }

    override fun hashCode(): Int = if (wasSuspended) 0 else 1  // we cannot use the value here
}

/**
 * Type of result used if the actor invocation does not return value.
 */
object VoidResult : Result() {
    override val wasSuspended get() = false
    override fun toString() = wasSuspendedPrefix + VOID
}

object SuspendedVoidResult : Result() {
    override val wasSuspended get() = true
    override fun toString() = wasSuspendedPrefix + VOID
}

private const val VOID = "void"

object Cancelled : Result() {
    override val wasSuspended get() = true
    override fun toString() = wasSuspendedPrefix + "CANCELLED"
}

/**
 * Type of result used if the actor invocation fails with the specified in {@link Operation#handleExceptionsAsResult()} exception [tClazz].
 */
@Suppress("DataClassPrivateConstructor")
data class ExceptionResult private constructor(val tClazz: Class<out Throwable>, override val wasSuspended: Boolean) : Result() {
    override fun toString() = wasSuspendedPrefix + tClazz.simpleName

    companion object {
        @Suppress("UNCHECKED_CAST")
        @JvmOverloads
        fun create(tClazz: Class<out Throwable>, wasSuspended: Boolean = false) = ExceptionResult(tClazz.normalize(), wasSuspended)
    }
}
// for byte-code generation
@JvmSynthetic
fun createExceptionResult(tClazz: Class<out Throwable>) = ExceptionResult.create(tClazz, false)

/**
 * Type of result used if the actor invocation suspended the thread and did not get the final result yet
 * though it can be resumed later
 */
object NoResult : Result() {
    override val wasSuspended get() = false
    override fun toString() = "-"
}

object Suspended : Result() {
    override val wasSuspended get() = true
    override fun toString() = "S"
}

/**
 * Type of result used for verification.
 * Resuming thread writes result of the suspension point and continuation to be executed in the resumed thread into [contWithSuspensionPointRes].
 */
internal data class ResumedResult(val contWithSuspensionPointRes: Pair<Continuation<Any?>?, kotlin.Result<Any?>>) : Result() {
    override val wasSuspended: Boolean get() = true

    lateinit var resumedActor: Actor
    lateinit var by: Actor
}

object CrashResult : Result() {
    override val wasSuspended get() = false
    override fun toString() = "CRASH"
}

// for byte-code generation
@JvmSynthetic
fun creteCrashResult() = CrashResult
