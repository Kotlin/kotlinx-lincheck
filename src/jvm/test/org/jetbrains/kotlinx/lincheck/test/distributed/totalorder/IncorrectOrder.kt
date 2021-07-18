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

package org.jetbrains.kotlinx.lincheck.test.distributed.totalorder

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test

data class SimpleMessage(val id: Int, val from: Int)

class IncorrectOrder(env: Environment<SimpleMessage, SimpleMessage>) : OrderCheckNode<SimpleMessage>(env)  {
    var opId = 0
    override fun onMessage(message: SimpleMessage, sender: Int) {
        env.log.add(message)
    }

    @Operation
    fun broadcast() {
        opId++
        env.broadcast(SimpleMessage(opId, env.nodeId), skipItself = false)
    }
}

class IncorrectOrderTest {
    @Test(expected = LincheckAssertionError::class)
    fun test() {
        LinChecker.check(IncorrectOrder::class.java,
            DistributedOptions<SimpleMessage, SimpleMessage>()
                .requireStateEquivalenceImplCheck(false)
                .actorsPerThread(3)
                .threads(3)
                .invocationsPerIteration(3_000)
                .iterations(1)
                .verifier(EpsilonVerifier::class.java))
    }
}