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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test

class Smoke(val env: Environment<Int, Unit>) : Node<Int> {
    override fun onMessage(message: Int, sender: Int) {
        if (message == 1) {
            env.send(0, sender)
        }
    }

    @Operation
    suspend fun op() {
        env.broadcast(1)
    }
}


class SmokeTest {
    @Test
    fun test() {
        LinChecker.check(
            Smoke::class.java,
            DistributedOptions<Int, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .actorsPerThread(2)
                .threads(3)
                .invocationsPerIteration(300)
                .setMaxNumberOfFailedNodes { it / 2 }
                .iterations(100)
                .crashMode(CrashMode.ALL_NODES_RECOVER)
                .verifier(EpsilonVerifier::class.java)
        )
    }
}

