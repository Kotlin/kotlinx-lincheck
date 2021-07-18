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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration

class NodeCrashInfo(
    private val testCfg: DistributedCTestConfiguration<*, *>,
    private val context: ModelCheckingContext<*, *>
) {
    val maxNumberOfFailedNodes = testCfg.maxNumberOfFailedNodes(context.addressResolver.totalNumberOfNodes)
    var numberOfFailedNodes: Int = 0
    val partitions = mutableListOf(emptySet(), (0 until context.addressResolver.totalNumberOfNodes).toSet())
    val failedNodes = Array<Boolean>(context.addressResolver.totalNumberOfNodes) {
        false
    }
    var partitionCount: Int = 0

    fun canSend(a: Int, b: Int): Boolean {
        return (partitions[0].contains(a) && partitions[0].contains(b) ||
                partitions[1].contains(a) && partitions[1].contains(b))
    }

    operator fun get(a: Int) = failedNodes[a]

    fun crashNode(iNode: Int): Boolean {
        if (numberOfFailedNodes == maxNumberOfFailedNodes ||
            failedNodes[iNode]
        ) return false
        failedNodes[iNode] = true
        numberOfFailedNodes++
        return true
    }

    fun recoverNode(iNode: Int) {
        check(failedNodes[iNode])
        failedNodes[iNode] = false
        numberOfFailedNodes--
    }
}