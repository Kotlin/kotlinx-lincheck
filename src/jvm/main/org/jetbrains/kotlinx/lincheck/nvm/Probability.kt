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
package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

abstract class ProbabilityModel {
    protected abstract fun actorProbability(iThread: Int, iActor: Int): Double
    abstract fun isCrashAllowed(crashesNumber: Int): Boolean
    fun crashPointProbability(iThread: Int, j: Int): Double {
        val i = Statistics.currentActor(iThread)
        val c = actorProbability(iThread, i)
        return c / (1 - j * c)
    }
}

internal object NoCrashesProbabilityModel : ProbabilityModel() {
    override fun actorProbability(iThread: Int, iActor: Int) = 0.0
    override fun isCrashAllowed(crashesNumber: Int) = false
}

internal class DurableProbabilityModel : ProbabilityModel() {
    private val c: DoubleArray
    private val s: Array<DoubleArray>

    init {
        val length = Statistics.length
        val maxCrashes = Probability.expectedCrashes
        c = DoubleArray(length.size) { i -> findC(length[i], maxCrashes) }
        s = Array(length.size) { i -> calculatePreviousCrashesProbabilities(length[i], c[i], maxCrashes) }
    }

    override fun actorProbability(iThread: Int, iActor: Int) =
        if (iActor == 0) c[iThread] else c[iThread] / s[iThread][iActor - 1]

    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < Probability.expectedCrashes

    private fun findC(actors: DoubleArray, maxCrashes: Int): Double {
        val n = actors.size
        val total = actors.sum()
        if (total <= 0.0) return 0.0
        val w0 = DoubleArray(n + 1)
        val w1 = DoubleArray(n + 1)
        val s = DoubleArray(n)
        val solver = BinarySearchSolver { c ->
            val p = calculatePreviousCrashesProbabilities(actors, c, maxCrashes, w0, w1, s)
            c - (0 until n).minOf { i -> (if (i == 0) 1.0 else p[i - 1]) / actors[i] }
        }
        val mc = min(1.0 / actors.maxOrNull()!!, maxCrashes.toDouble() / total)
        return solver.solve(0.0, mc, 1e-9)
    }

    private fun calculatePreviousCrashesProbabilities(
        actors: DoubleArray,
        c: Double,
        maxCrashes: Int,
        w0: DoubleArray = DoubleArray(actors.size + 1),
        w1: DoubleArray = DoubleArray(actors.size + 1),
        s: DoubleArray = DoubleArray(actors.size)
    ): DoubleArray {
        var w0 = w0
        val n = actors.size
        if (n == 0) return s
        w0.fill(0.0, 2)
        w0[0] = 1 - c * actors[0]; w0[1] = c * actors[0]
        s[0] = w0[0] + if (maxCrashes == 1) 0.0 else w0[1]
        for (i in 1 until n) {
            val z = c * actors[i] / s[i - 1]
            w1[0] = (1 - z) * w0[0]
            for (k in 1..n) {
                w1[k] = z * w0[k - 1] + (1 - z) * w0[k]
            }
            for (k in 0 until maxCrashes) {
                s[i] += w1[k]
            }
            w0 = w1
        }
        return s
    }
}

internal class DetectableExecutionProbabilityModel : ProbabilityModel() {
    private val c: Double

    init {
        val n = Statistics.totalLength
        val e = Probability.expectedCrashes.toDouble()
        c = if (n > 0) e / n else 0.0
    }

    override fun actorProbability(iThread: Int, iActor: Int) = c / (1 + c * Statistics.actorLength(iThread, iActor))
    override fun isCrashAllowed(crashesNumber: Int) = crashesNumber < 2 * Probability.expectedCrashes
}

internal object Statistics {
    private val actor = IntArray(NVMCache.MAX_THREADS_NUMBER)
    private val temporary = IntArray(NVMCache.MAX_THREADS_NUMBER)
    private val sum = Array(NVMCache.MAX_THREADS_NUMBER) { intArrayOf() }
    private val count = Array(NVMCache.MAX_THREADS_NUMBER) { intArrayOf() }
    val length = Array(NVMCache.MAX_THREADS_NUMBER) { doubleArrayOf() }
    internal var maxLength = 0.0
        private set
    internal var totalLength = 0.0
        private set

    internal fun onEnterActorBody(iThread: Int, iActor: Int) {
        temporary[iThread] = 0
        actor[iThread] = iActor
    }

    internal fun onExitActorBody(iThread: Int, iActor: Int) {
        sum[iThread][iActor] += temporary[iThread]
        count[iThread][iActor]++
        temporary[iThread] = 0
    }

    internal fun onNewInvocation() {
        maxLength = 0.0
        totalLength = 0.0
        length.forEachIndexed { iThread, l ->
            l.indices.forEach { iActor ->
                val length =
                    if (count[iThread][iActor] == 0) 0.0
                    else sum[iThread][iActor].toDouble() / count[iThread][iActor]
                l[iActor] = length
                maxLength = max(maxLength, length)
                totalLength += length
            }
        }
    }

    internal fun reset(scenario: ExecutionScenario) {
        count[0] = IntArray(scenario.initExecution.size)
        for (i in scenario.parallelExecution.indices) {
            count[i + 1] = IntArray(scenario.parallelExecution[i].size)
        }
        count[scenario.threads + 1] = IntArray(scenario.postExecution.size)

        count.forEachIndexed { i, arr ->
            sum[i] = IntArray(arr.size)
            length[i] = DoubleArray(arr.size)
        }
        temporary.fill(0)
    }

    internal fun onCrashPoint(iThread: Int) = temporary[iThread]++
    internal fun actorLength(iThread: Int, iActor: Int) = length[iThread][iActor]
    internal fun currentActor(iThread: Int) = actor[iThread]
}

object Probability {
    private const val RANDOM_FLUSH_PROBABILITY = 0.05
    private val random get() = randomGetter()

    @Volatile
    private lateinit var randomGetter: () -> Random
    private var seed = 0L
    private val mcRandom = Random(42)
    private var minimizeCrashes = false

    @Volatile
    private var randomSystemCrashProbability = 0.0

    @Volatile
    internal var expectedCrashes = 0

    @Volatile
    private lateinit var model: ProbabilityModel

    fun shouldSystemCrash() = bernoulli(randomSystemCrashProbability)
    fun shouldFlush() = bernoulli(RANDOM_FLUSH_PROBABILITY)
    fun shouldCrash(): Boolean {
        if (!NVMState.crashesEnabled) return false
        val iThread = NVMState.threadId()
        val j = Statistics.onCrashPoint(iThread)
        return moreCrashesPermitted() && bernoulli(model.crashPointProbability(iThread, j))
    }

    fun resetExpectedCrashes() {
        minimizeCrashes = false
    }

    fun minimizeCrashes(crashes: Int) {
        minimizeCrashes = true
        expectedCrashes = crashes
    }

    fun setNewInvocation(recoverModel: RecoverabilityModel) {
        Statistics.onNewInvocation()
        model = recoverModel.createProbabilityModel()
    }

    fun reset(scenario: ExecutionScenario, recoverModel: RecoverabilityModel) {
        randomGetter = { ThreadLocalRandom.current() }
        if (!minimizeCrashes) {
            expectedCrashes = recoverModel.defaultExpectedCrashes()
        }
        randomSystemCrashProbability = recoverModel.systemCrashProbability()
        Statistics.reset(scenario)
    }

    private fun moreCrashesPermitted() = model.isCrashAllowed(occurredCrashes())
    private fun occurredCrashes() = if (randomSystemCrashProbability < 1.0) {
        NVMState.crashesCount()
    } else {
        NVMState.maxCrashesCountPerThread()
    }

    private fun bernoulli(probability: Double) = random.nextDouble() < probability

    internal fun setSeed(seed: Int) {
        this.seed = seed.toLong()
    }

    internal fun resetRandom() {
        mcRandom.setSeed(seed)
        randomGetter = { mcRandom }
    }
}

internal fun interface BinarySearchSolver {
    /**
     *  A monotonically increasing function.
     */
    fun f(x: Double): Double

    /**
     * Find x such that f(x) = 0. x in [[a], [b]].
     */
    fun solve(a: Double, b: Double, eps: Double): Double {
        var l = a
        var r = b
        while (r - l > eps) {
            val x = (r + l) / 2
            val fValue = f(x)
            when {
                fValue > 0 -> r = x
                fValue < 0 -> l = x
                else -> return x
            }
        }
        return l
    }
}
