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

import kotlin.random.Random

interface IExecutionEmulator {
    fun emulate(
        results: IntArray,
        crashes: MutableList<Int>,
        iterations: Int
    )
}

internal class ExecutionEmulator(
    private val statistics: StatisticsModel,
    private val model: CrashProbabilityModel,
    private val random: Random,
) : IExecutionEmulator {
    private val actors get() = statistics.actorLengths
    private val recoveries get() = statistics.recoveryLengths

    override fun emulate(
        results: IntArray,
        crashes: MutableList<Int>,
        iterations: Int
    ) {
        val n = actors.size + actors.size
        repeat(iterations) {
            var i = 0
            var j = 0
            var passed = 0
            var ic = 0
            while (i < n) {
                val inActor = i and 1 == 0
                if (inActor) {
                    if (j == actors[i / 2]) {
                        passed += recoveries[i / 2]
                        i += 2
                        j = 0
                        continue
                    }
                } else {
                    if (j == recoveries[i / 2]) {
                        i++
                        j = 0
                        continue
                    }
                }
                val f = if (inActor) model.inActorCrashProbability(i / 2) else model.inRecoveryCrashProbability(i / 2)
                val crash = model.isCrashAllowed(ic) && random.nextDouble() < model.crashProbability(j, f)
                if (crash) {
                    results[passed]++
                    if (inActor) {
                        passed += actors[i / 2] - j
                        i++
                    } else {
                        passed -= j
                    }
                    j = 0
                    ic++
                } else {
                    j++
                    passed++
                }
            }
            crashes.add(ic)
        }
    }
}

internal class DetectableExecutionEmulator(
    private val statistics: StatisticsModel,
    private val model: CrashProbabilityModel,
    private val random: Random,
): IExecutionEmulator {
    private val actors get() = statistics.actorLengths

    override fun emulate(
        results: IntArray,
        crashes: MutableList<Int>,
        iterations: Int
    ) {
        require(statistics.recoveryLengths.all { it == 0 })
        val n = actors.size
        repeat(iterations) {
            var i = 0
            var j = 0
            var passed = 0
            var ic = 0
            while (i < n) {
                if (j == actors[i]) {
                    i++
                    j = 0
                    continue
                }
                val f = model.inActorCrashProbability(i)
                val crash = model.isCrashAllowed(ic) && random.nextDouble() < model.crashProbability(j, f)
                if (crash) {
                    results[passed]++
                    passed -= j
                    j = 0
                    ic++
                } else {
                    j++
                    passed++
                }
            }
            crashes.add(ic)
        }
    }
}
