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

package org.jetbrains.kotlinx.lincheck.test.transformation.crash

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

@StressCTest(recover = Recover.DURABLE, minimizeFailedScenario = false, iterations = 1, invocationsPerIteration = 2)
internal class CrashTransformerDealsWithCatchThrowableTest : VerifierState() {
    private val c = NVMClassWithCatchThrowable()

    @Test
    fun testDealsWithCatchThrowable() {
        LinChecker.check(CrashTransformerDealsWithCatchThrowableTest::class.java)
    }

    override fun extractState() = c.x.value

    @Operation
    fun foo() = c.foo()
}


private class NVMClassWithCatchThrowable {
    val x = nonVolatile(0)
    var y = 4

    @Recoverable
    fun foo(): Int {
        try {
            x.value = 42
            x.compareAndSet(1, 2)
            y = hashCode()
            return 5
        } catch (e: Throwable) {
            assert(false) // should not ever happen as CrashError is rethrown by LinCheck
        }
        return 42
    }
}
