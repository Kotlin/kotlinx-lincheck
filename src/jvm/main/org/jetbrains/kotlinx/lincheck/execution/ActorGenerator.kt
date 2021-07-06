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
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import java.lang.reflect.*
import kotlin.random.*

/**
 * Implementations of this class generate [actors][Actor]
 * using [parameter generators][ParameterGenerator].
 */
class ActorGenerator(
    private val method: Method,
    private val parameterGenerators: List<ParameterGenerator<*>>,
    private val handledExceptions: List<Class<out Throwable?>>,
    val useOnce: Boolean,
    cancellableOnSuspension: Boolean,
    private val allowExtraSuspension: Boolean,
    private val blocking: Boolean,
    private val causesBlocking: Boolean,
    promptCancellation: Boolean
) {
    private val cancellableOnSuspension = cancellableOnSuspension && isSuspendable
    private val promptCancellation = cancellableOnSuspension && promptCancellation

    fun generate(threadId: Int, operationId: Int): Actor {
        val threadIdIndices = mutableListOf<Int>()
        val parameters = parameterGenerators
            .map { if (it is OperationIdGen) operationId else it.generate() }
            .mapIndexed { index, value ->
                if (value === THREAD_ID_TOKEN) {
                    threadIdIndices.add(index)
                    threadId
                } else {
                    value
                }
            }
        val cancelOnSuspension = this.cancellableOnSuspension and DETERMINISTIC_RANDOM.nextBoolean()
        val promptCancellation = cancelOnSuspension and this.promptCancellation and DETERMINISTIC_RANDOM.nextBoolean()
        return Actor(
            method = method,
            arguments = parameters.toMutableList(),
            handledExceptions = handledExceptions,
            cancelOnSuspension = cancelOnSuspension,
            allowExtraSuspension = allowExtraSuspension,
            blocking = blocking,
            causesBlocking = causesBlocking,
            promptCancellation = promptCancellation,
            threadIdArgsIndices = threadIdIndices
        )
    }

    val isSuspendable: Boolean get() = method.isSuspendable()
    override fun toString() = method.toString()
}

private val DETERMINISTIC_RANDOM = Random(42)
