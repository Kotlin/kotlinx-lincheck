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

package org.jetbrains.kotlinx.lincheck.test.transformation.crash.distribution

import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.runner.Runner
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

internal class SequentialCodeTest : VerifierState() {
    private val s = SequentialCodeClass()

    @Operation
    fun foo1() = s.foo1()

    @Operation
    fun foo2() = s.foo2()
    override fun extractState() = s.x
}

private class SequentialCodeClass {
    val x = nonVolatile(0)

    fun foo1() {
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
    }

    fun foo2() {
        x.flush()
        x.flush()
        x.flush()
        x.flush()
    }
}

private data class CrashPosition(val iActor: Int, val line: Int) : Comparable<CrashPosition> {
    override fun compareTo(other: CrashPosition): Int {
        if (iActor != other.iActor) return iActor - other.iActor
        return line - other.line
    }
}

class UniformDistributedCrashesTest {
    @Test
    fun testDurable() = test(/* 2 foo1 + 3 foo2 */37, Recover.DURABLE, SequentialCodeTest::class.java, 1)

    @Test
    @Ignore("This model works only with unbounded number of crashes. But this is uncomfortable to use & analyze.")
    fun testDetectableExecution() =
        test(/* 2 foo1 + 3 foo2 */37, Recover.DETECTABLE_EXECUTION, SequentialCodeTest::class.java, 5)

    private fun test(crashPoints: Int, model: Recover, testClass: Class<*>, expectedCrashes: Int) {
        val n = 1_000_000
        val testConfiguration = StressOptions().run {
            recover(model)
            threads(1)
        }.createTestConfigurations(testClass)
        val scenario = generateScenario(testConfiguration, testClass)
        val strategy = createStrategy(testConfiguration, testClass, scenario)
        val runner = getRunner(strategy)

        val positions = hashMapOf<CrashPosition, Int>()
        var totalCrashes = 0
        var maxCrashes = 0
        repeat(n) {
            val res = runner.run()
            check(res is CompletedInvocationResult)
            res.results.crashes.flatten().forEach { e ->
                val c = CrashPosition(e.actorIndex, e.crashStackTrace[0].lineNumber)
                positions[c] = (positions[c] ?: 0) + 1
            }
            val crashes = res.results.crashes.maxOf { it.size }
            totalCrashes += crashes
            maxCrashes = max(maxCrashes, crashes)
        }

        println("Maximum number of crashes is $maxCrashes")
        assertEquals(crashPoints, positions.size)
        val averageCrashes = totalCrashes.toDouble() / n
        assertTrue(
            "Expected $expectedCrashes crashes, but average crashes is $averageCrashes",
            abs((averageCrashes - expectedCrashes) / expectedCrashes) < 0.05
        )
        val expected = positions.values.sum() / crashPoints.toDouble()
        positions.toSortedMap().forEach { (k, v) -> println("$k -- $v, dev=${(v - expected) / expected}") }
        positions.toSortedMap().values.forEach { c ->
            val deviation = (c - expected) / expected
            assertTrue("Deviation is $deviation: expected $expected crashes but $c found", abs(deviation) < 0.05)
        }
    }
}

private fun createStrategy(
    testConfiguration: StressCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario
) = testConfiguration.createStrategy(testClass, scenario, emptyList(), null, EpsilonVerifier(testClass))

private fun generateScenario(
    testConfiguration: StressCTestConfiguration,
    testClass: Class<*>
) = RandomExecutionGenerator(testConfiguration, CTestStructure.getFromTestClass(testClass)).nextExecution()

private fun getRunner(strategy: StressStrategy) = StressStrategy::class.java
    .getDeclaredField("runner")
    .also { it.isAccessible = true }
    .get(strategy) as Runner
