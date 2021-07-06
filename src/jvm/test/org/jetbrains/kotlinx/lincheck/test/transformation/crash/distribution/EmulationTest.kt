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

import org.junit.Assert
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

private const val ITERATIONS = 1_000_000
private const val REPEATS = 10

internal class EmulationTest {

    @Test
    fun testBasicUniformDistribution() {
        val random = Random(42)
        repeat(REPEATS) {
            val n = random.nextInt(1, 10)
            val actors = List(n) { random.nextInt(1, 20) }
            val recovery = List(n) { random.nextInt(0, 20) }
            val statistics = StatisticsModel(actors, recovery)
            val expected = random.nextDouble(0.99, BasicCrashProbabilityModel.maxExpectedCrashes(statistics))
            val model = BasicCrashProbabilityModel(statistics, expected)
            val emulator = ExecutionEmulator(statistics, model, random)
            testScenarioUniformDistribution(statistics, model, emulator)
        }
    }

    @Test
    fun testBasicDetectableExecutionUniformDistribution() {
        val random = Random(42)
        repeat(REPEATS) {
            val n = random.nextInt(1, 10)
            val actors = List(n) { random.nextInt(1, 20) }
            val recovery = List(n) { 0 }
            val statistics = StatisticsModel(actors, recovery)
            val expected = random.nextDouble(0.99, 20.0)
            val model = BasicDetectableExecutionCrashProbabilityModel(statistics, expected)
            val emulator = DetectableExecutionEmulator(statistics, model, random)
            testScenarioUniformDistribution(statistics, model, emulator)
        }
    }

    @Test
    fun testBoundedNoCrashesUniformDistribution() {
        val random = Random(42)
        repeat(REPEATS) {
            val n = random.nextInt(1, 10)
            val actors = List(n) { random.nextInt(1, 20) }
            val recovery = List(n) { 0 }
            val statistics = StatisticsModel(actors, recovery)
            val maxCrashes = random.nextInt(1, n + 1)
            val maxE = BoundedNoRecoverCrashProbabilityModel.expectedCrashes(statistics, maxCrashes)
            val expected = random.nextDouble(0.5, maxE)
            val model = BoundedNoRecoverCrashProbabilityModel(statistics, expected, maxCrashes)
            val emulator = ExecutionEmulator(statistics, model, random)
            testScenarioUniformDistribution(statistics, model, emulator)
        }
    }

    @Test
    fun testBoundedUniformDistributionOneActor() {
        repeat(REPEATS) {
            val random = Random(it)
            val n = 1
            val maxCrashes = random.nextInt(1, 10)
            val actors = List(n) { random.nextInt(1, 20) }
            val recovery = List(n) { i -> if (maxCrashes == 1) 0 else random.nextInt(0, actors[i] * (maxCrashes - 1)) }
            val statistics = StatisticsModel(actors, recovery)
            val maxE = BoundedCrashProbabilityModelOneActor.expectedCrashes(statistics, maxCrashes)
            val expected = random.nextDouble(maxE / 2, maxE)
            val model = BoundedCrashProbabilityModelOneActor(statistics, expected, maxCrashes)
            val emulator = ExecutionEmulator(statistics, model, random)
            testScenarioUniformDistribution(statistics, model, emulator)
        }
    }
}

private fun testScenarioUniformDistribution(
    statistics: StatisticsModel,
    model: CrashProbabilityModel,
    emulator: IExecutionEmulator
) {
    val results = IntArray(statistics.actorLengths.sum() + statistics.recoveryLengths.sum())
    val crashes = mutableListOf<Int>()
    emulator.emulate(results, crashes, ITERATIONS)
    verifyResults(results, crashes, model)
}

private fun verifyResults(results: IntArray, crashes: List<Int>, model: CrashProbabilityModel) {
    val expectedCrashes = model.expectedCrashes
    val averageCrashesPerScenario = crashes.average()
    val d = crashes.sumByDouble { x -> (x - averageCrashesPerScenario).pow(2) } / (ITERATIONS - 1)
    println("sqrt(D) / E = ${d / expectedCrashes}")
    Assert.assertTrue(abs(averageCrashesPerScenario - expectedCrashes) / expectedCrashes < 0.05)
    val expected = results.sum() / results.size.toDouble()
    results.forEach { cr ->
        val deviation = (cr - expected) / expected
        Assert.assertTrue(abs(deviation) < 0.05)
    }
    Assert.assertTrue(crashes.maxOrNull()!! <= model.maxCrashes)
}