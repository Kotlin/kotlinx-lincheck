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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.*
import kotlin.reflect.jvm.*

class ClocksTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    @Volatile
    private var bStarted = false

    @Operation
    fun a() {
        // do nothing
    }

    @Operation
    fun b() {
        bStarted = true
    }

    @Operation
    fun c() {
        while (!bStarted) {} // wait until `a()` is completed
    }

    @Operation
    fun d(): Int {
        return 0 // cannot return 0, should fail
    }

    override fun <O : Options<O, *>> O.customize() {
        executionGenerator(ClocksTestScenarioGenerator::class.java)
        iterations(1)
        sequentialSpecification(ClocksTestSequential::class.java)
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)
    }
}

class ClocksTestScenarioGenerator(testCfg: CTestConfiguration, testStructure: CTestStructure)
    : ExecutionGenerator(testCfg, testStructure)
{
    override fun nextExecution() = ExecutionScenario(
        emptyList(),
        listOf(
            listOf(
                Actor(method = ClocksTest::a.javaMethod!!, arguments = mutableListOf()),
                Actor(method = ClocksTest::b.javaMethod!!, arguments = mutableListOf())
            ),
            listOf(
                Actor(method = ClocksTest::c.javaMethod!!, arguments = mutableListOf()),
                Actor(method = ClocksTest::d.javaMethod!!, arguments = mutableListOf())
            )
        ),
        emptyList()
    )

}

class ClocksTestSequential {
    private var x = 0

    fun a() {
        x = 1
    }

    fun b() {}
    fun c() {}

    fun d(): Int = x
}
