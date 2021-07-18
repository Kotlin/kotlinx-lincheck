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

package org.jetbrains.kotlinx.lincheck.test.distributed

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test

class NodeThrowsException(val env: Environment<Int, Unit>): Node<Int> {
    override fun onMessage(message: Int, sender: Int) {
        if (message != 0) {
            env.broadcast(0)
        }
    }

    @Operation
    suspend fun op() {
        env.broadcast(1)
        throw RuntimeException()
    }
}

class SimpleExceptionTest {
    @Test(expected = LincheckAssertionError::class)
    fun test() {
        LinChecker.check(
            NodeThrowsException::class.java,
            DistributedOptions<Int, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .actorsPerThread(2)
                .threads(3)
                .invocationsPerIteration(10)
                .iterations(1)
                .verifier(EpsilonVerifier::class.java)
        )
    }
}