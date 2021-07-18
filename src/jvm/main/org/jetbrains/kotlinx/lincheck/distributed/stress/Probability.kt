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

package org.jetbrains.kotlinx.lincheck.distributed.stress

import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode
import java.io.BufferedWriter
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.random.Random

val debugProb = false

fun addToFile(f: (BufferedWriter) -> Unit) {
    if (!debugProb) return
    FileOutputStream("crash_stats.txt", true).bufferedWriter().use {
        f(it)
    }
}

class Probability(
    private val testCfg: DistributedCTestConfiguration<*, *>,
    private val context: DistributedRunnerContext<*, *>,
    private val iNode: Int
) {
    companion object {
        val rand: ThreadLocal<Random> = ThreadLocal.withInitial { Random(Thread.currentThread().hashCode()) }
        const val MESSAGE_SENT_PROBABILITY = 0.95
        const val MESSAGE_DUPLICATION_PROBABILITY = 0.9
        const val NODE_FAIL_PROBABILITY = 0.05
        const val NODE_RECOVERY_PROBABILITY = 0.7
        var failedNodesExpectation = -1
        var networkPartitionsExpectation = 8
        val networkRecoveryTimeout = 10
    }

    private val numberOfNodes: Int = context.addressResolver.totalNumberOfNodes

    fun duplicationRate(): Int {
        if (!messageIsSent()) {
            return 0
        }
        if (!testCfg.messageDuplication) {
            return 1
        }
        return if (rand.get().nextDouble(1.0) < MESSAGE_DUPLICATION_PROBABILITY) 1 else 2
    }

    private fun messageIsSent(): Boolean {
        if (testCfg.isNetworkReliable) {
            return true
        }
        return rand.get().nextDouble(1.0) < MESSAGE_SENT_PROBABILITY
    }

    fun nodeFailed(maxNumCanFail : Int): Boolean {
        if (maxNumCanFail == 0) {
            context.nextNumberOfCrashes.lazySet(0)
            return false
        }
        while (true) {
            val numberOfCrashes = context.nextNumberOfCrashes.value
            if (numberOfCrashes == 0) break
            if (context.nextNumberOfCrashes.compareAndSet(numberOfCrashes, numberOfCrashes - 1)) {
                return true
            }
        }
        val r = rand.get().nextDouble(1.0)
        val p = nodeFailProbability()
        if (r < p) addToFile {
            it.appendLine("[$iNode]: $curMsgCount")
        }
        if (r >= p) return false
        //println("True")
        context.nextNumberOfCrashes.lazySet(rand.get().nextInt(1, maxNumCanFail + 1) - 1)
        return true
    }

    fun nodeRecovered(): Boolean = rand.get().nextDouble(1.0) < NODE_RECOVERY_PROBABILITY

    var prevMsgCount = 0

    var curMsgCount = 0

    var iterations = 0

    private fun nodeFailProbability(): Double {
        //return NODE_FAIL_PROBABILITY
        return if (prevMsgCount == 0) {
            0.0
        } else {
            val q = failedNodesExpectation.toDouble() / numberOfNodes
            return if (testCfg.supportRecovery == CrashMode.NO_RECOVERIES) {
                q / (prevMsgCount - (curMsgCount - 1) * q)
                //q / prevMsgCount
            } else {
                q / prevMsgCount
            }
        }
    }

    fun isNetworkPartition(): Boolean {
        if (prevMsgCount == 0) return false
        val q = networkPartitionsExpectation.toDouble() / numberOfNodes
        val p = q / prevMsgCount
        val r = rand.get().nextDouble(1.0)
        return r < p
    }

    fun networkRecoveryDelay() = rand.get().nextInt(1, networkRecoveryTimeout)

    fun reset(failedNodesExp: Int = 0) {
        if (failedNodesExpectation == -1) failedNodesExpectation = failedNodesExp
        addToFile {
            it.appendLine("[$iNode]: curMsgCount = $curMsgCount, prevMsgCount = $prevMsgCount")
            it.appendLine("----------------------------")
            it.newLine()
        }
        prevMsgCount = max(prevMsgCount, curMsgCount)
        curMsgCount = 0
    }
}