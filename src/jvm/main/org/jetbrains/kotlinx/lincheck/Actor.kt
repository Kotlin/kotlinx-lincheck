/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import java.lang.reflect.Method
import kotlin.reflect.jvm.*

/**
 * The actor entity describe the operation with its parameters
 * which is executed during the testing.
 *
 * @see Operation
 */
data class Actor @JvmOverloads constructor(
    val method: Method,
    val arguments: MutableList<Any?>,
    val handledExceptions: List<Class<out Throwable>> = emptyList(),
    val cancelOnSuspension: Boolean = false,
    val allowExtraSuspension: Boolean = false,
    val blocking: Boolean = false,
    val causesBlocking: Boolean = false,
    val promptCancellation: Boolean = false,
    // we have to specify `isSuspendable` property explicitly for transformed classes since
    // `isSuspendable` implementation produces a circular dependency and, therefore, fails.
    val isSuspendable: Boolean = method.isSuspendable(),
    val threadIdArgsIndices: List<Int> = emptyList()
) {
    init {
        if (promptCancellation) require(cancelOnSuspension) {
            "`promptCancellation` cannot be set to `true` if `cancelOnSuspension` is `false`"
        }
    }

    override fun toString() = method.name +
        arguments.joinToString(prefix = "(", postfix = ")", separator = ", ") { it.toString() } +
        (if (cancelOnSuspension) " + " else "") +
        (if (promptCancellation) "prompt_" else "") +
        (if (cancelOnSuspension) "cancel" else "")

    val handlesExceptions = handledExceptions.isNotEmpty()

    fun setThreadId(threadId: Int) = threadIdArgsIndices.forEach { index -> arguments[index] = threadId }
}

fun Method.isSuspendable(): Boolean = kotlinFunction?.isSuspend ?: false