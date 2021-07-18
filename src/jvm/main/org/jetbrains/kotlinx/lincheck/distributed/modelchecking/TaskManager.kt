/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.runner.TestNodeExecution
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.random.Random

sealed class Task {
    abstract val iNode: Int
    abstract val clock: VectorClock
    abstract val msg: String
    abstract val f: suspend () -> Unit
    fun goesBefore(other: Task) : Boolean {
        return other.iNode >= iNode || clock.happensBefore(other.clock)
    }
}

data class MessageReceiveTask(override val iNode: Int, override val clock: VectorClock, override val msg: String, override val f: suspend () -> Unit) : Task()
data class OperationTask(override val iNode: Int, override val clock: VectorClock, override val msg: String, override val f: suspend () -> Unit) : Task()
data class NodeCrashTask(override val iNode: Int, override val clock: VectorClock, override val msg: String, override val f: suspend () -> Unit) : Task()