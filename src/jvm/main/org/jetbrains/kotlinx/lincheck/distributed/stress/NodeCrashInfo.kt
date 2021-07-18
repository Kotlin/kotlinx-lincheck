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
import org.jetbrains.kotlinx.lincheck.distributed.NetworkPartitionMode
import org.jetbrains.kotlinx.lincheck.distributed.Node

abstract class NodeCrashInfo(
    protected val testCfg: DistributedCTestConfiguration<*, *>,
    protected val context: DistributedRunnerContext<*, *>,
    val numberOfFailedNodes: Int,
    val failedNodes: List<Boolean>,
    val failedNodesForType: Map<Class<out Node<*>>, Int>
) {
    companion object {
        fun initialInstance(
            testCfg: DistributedCTestConfiguration<*, *>,
            context: DistributedRunnerContext<*, *>
        ): NodeCrashInfo {
            val numberOfNodes = context.addressResolver.totalNumberOfNodes
            val failedNodes = List(numberOfNodes) { false }
            return if (testCfg.networkPartitions != NetworkPartitionMode.SINGLE) {
                val partitions = listOf(emptySet(), (0 until numberOfNodes).toSet())
                NodeCrashInfoHalves(testCfg, context, 0, partitions, failedNodes, mutableMapOf(), 0)
            } else {
                val edges = Array(context.addressResolver.totalNumberOfNodes) {
                    (0 until context.addressResolver.totalNumberOfNodes).toSet()
                }.toList()
                NodeCrashInfoSingle(testCfg, context, 0, failedNodes, mutableMapOf(), edges)
            }
        }
    }

    val maxNumberOfFailedNodes = if (testCfg.maxNumberOfFailedNodes(context.addressResolver.totalNumberOfNodes) != 0) {
        testCfg.maxNumberOfFailedNodes(context.addressResolver.totalNumberOfNodes)
    } else {
        context.testCfg.maxNumberOfFailedNodesForType.entries.sumBy {
            it.value(testCfg.nodeTypes[it.key]!!.maxNumberOfInstances)
        }
    }

    val remainedNodes = maxNumberOfFailedNodes - numberOfFailedNodes

    abstract fun canSend(a: Int, b: Int): Boolean

    operator fun get(a: Int) = failedNodes[a]

    abstract fun crashNode(iNode: Int): NodeCrashInfo?

    abstract fun recoverNode(iNode: Int): NodeCrashInfo

    abstract fun setNetworkPartition(a: Int, b: Int): NodeCrashInfo?

    abstract fun recoverNetworkPartition(a: Int, b: Int): NodeCrashInfo
}

class NodeCrashInfoHalves(
    testCfg: DistributedCTestConfiguration<*, *>,
    context: DistributedRunnerContext<*, *>,
    numberOfFailedNodes: Int,
    val partitions: List<Set<Int>>,
    failedNodes: List<Boolean>,
    failedNodesForType: Map<Class<out Node<*>>, Int>,
    val partitionCount: Int,
) : NodeCrashInfo(testCfg, context, numberOfFailedNodes, failedNodes, failedNodesForType) {

    override fun canSend(a: Int, b: Int): Boolean {
        return (partitions[0].contains(a) && partitions[0].contains(b) ||
                partitions[1].contains(a) && partitions[1].contains(b))
    }

    override fun crashNode(iNode: Int): NodeCrashInfoHalves? {
        if (numberOfFailedNodes == maxNumberOfFailedNodes ||
            failedNodes[iNode]
        ) {
            //println("Cannot crash")
            return null
        }
        val cls = context.addressResolver[iNode]
        val maxNumberOfCrashes = context.addressResolver.maxNumberOfCrashesForNode(iNode)
        if (maxNumberOfCrashes == null || failedNodesForType[cls] == maxNumberOfCrashes) return null
        val newFailedNodes = failedNodes.toMutableList()
        val newFailedNodesForType = failedNodesForType.toMutableMap()
        newFailedNodesForType[cls] = newFailedNodesForType.getOrElse(cls) { 0 } + 1
        newFailedNodes[iNode] = true
        return NodeCrashInfoHalves(
            testCfg,
            context, numberOfFailedNodes + 1, partitions, newFailedNodes, newFailedNodesForType, partitionCount
        )
    }

    override fun recoverNode(iNode: Int): NodeCrashInfoHalves {
        check(failedNodes[iNode])
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = false
        val newFailedNodesForType = failedNodesForType.toMutableMap()
        val cls = context.addressResolver[iNode]
        newFailedNodesForType[cls] = newFailedNodesForType[cls]!! - 1
        return NodeCrashInfoHalves(
            testCfg,
            context,
            numberOfFailedNodes - 1,
            partitions,
            newFailedNodes,
            newFailedNodesForType,
            partitionCount
        )
    }

    override fun setNetworkPartition(a: Int, b: Int): NodeCrashInfoHalves? {
        if (partitions[0].isNotEmpty() || numberOfFailedNodes == maxNumberOfFailedNodes) return null
        val possiblePartitionSize = maxNumberOfFailedNodes - numberOfFailedNodes
        val rand = Probability.rand.get()
        val partitionSize = rand.nextInt(1, possiblePartitionSize + 1)
        check(partitionSize > 0)
        val nodesInPartition = failedNodes.mapIndexed { index, b -> index to b }
            .filter {
                !it.second && failedNodesForType[context.addressResolver[it.first]] != context.addressResolver.maxNumberOfCrashesForNode(
                    it.first
                )
            }.map { it.first }.shuffled(rand).take(partitionSize).toSet()
        check(nodesInPartition.isNotEmpty())
        val anotherPartition = (failedNodes.indices).filter { it !in nodesInPartition }.toSet()
        val newNumberOfFailedNodes = numberOfFailedNodes + partitionSize
        return NodeCrashInfoHalves(
            testCfg, context, newNumberOfFailedNodes, listOf(nodesInPartition, anotherPartition),
            failedNodes, failedNodesForType, partitionCount + 1
        )
    }

    override fun recoverNetworkPartition(a: Int, b: Int): NodeCrashInfoHalves {
        if (partitions[0].isEmpty()) return this
        val partitionSize = partitions[0].size
        return NodeCrashInfoHalves(
            testCfg, context, numberOfFailedNodes - partitionSize, listOf(emptySet(), failedNodes.indices.toSet()),
            failedNodes, failedNodesForType, partitionCount
        )
    }
}

class NodeCrashInfoSingle(
    testCfg: DistributedCTestConfiguration<*, *>,
    context: DistributedRunnerContext<*, *>,
    numberOfFailedNodes: Int,
    failedNodes: List<Boolean>,
    failedNodesForType: Map<Class<out Node<*>>, Int>,
    val edges: List<Set<Int>>
) : NodeCrashInfo(testCfg, context, numberOfFailedNodes, failedNodes, failedNodesForType) {
    override fun canSend(a: Int, b: Int): Boolean = failedNodes[a] || edges[a].contains(b)

    val numberOfNodes = context.addressResolver.totalNumberOfNodes

    private fun createNewEdges(iNode: Int): List<Set<Int>> {
        val newEdges = edges.map { it.toMutableSet() }.toMutableList()
        if (newEdges[iNode].isEmpty()) {
            newEdges[iNode] = (0 until numberOfNodes).toMutableSet()
            newEdges.filterIndexed { index, _ -> index != iNode }.forEach { it.add(iNode) }
        } else {
            newEdges[iNode] = mutableSetOf()
            newEdges.forEach { it.remove(iNode) }
        }
        return newEdges
    }

    override fun crashNode(iNode: Int): NodeCrashInfoSingle? {
        if (numberOfFailedNodes == maxNumberOfFailedNodes ||
            failedNodes[iNode]
        ) return null
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = true
        return NodeCrashInfoSingle(
            testCfg,
            context, numberOfFailedNodes + 1, newFailedNodes, failedNodesForType, createNewEdges(iNode)
        )
    }

    override fun recoverNode(iNode: Int): NodeCrashInfoSingle {
        check(failedNodes[iNode])
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = false
        return NodeCrashInfoSingle(
            testCfg,
            context, numberOfFailedNodes - 1, newFailedNodes, failedNodesForType, createNewEdges(iNode)
        )
    }

    private fun dfs(
        v: Int, visited: BooleanArray,
        component: MutableList<Int>, edges: List<Set<Int>>
    ) {
        if (visited[v]) return
        visited[v] = true
        component.add(v)
        for (i in edges[v]) dfs(i, visited, component, edges)
    }

    fun findMaxComponent(newEdges: List<Set<Int>>): List<Int> {
        val components = mutableListOf<MutableList<Int>>()
        val visited = BooleanArray(numberOfNodes)
        for (i in 0 until numberOfNodes) {
            val component = mutableListOf<Int>()
            dfs(i, visited, component, newEdges)
            if (component.isNotEmpty()) components.add(component)
        }
        return components.maxByOrNull { it.size }!!
    }

    override fun setNetworkPartition(a: Int, b: Int): NodeCrashInfo? {
        val newEdges = edges.map { it.toMutableSet() }
        newEdges[a].remove(b)
        newEdges[b].remove(a)
        val component = findMaxComponent(newEdges)
        val newFailedNodes = numberOfNodes - component.size
        if (newFailedNodes > maxNumberOfFailedNodes) return null
        return NodeCrashInfoSingle(testCfg, context, newFailedNodes, failedNodes, failedNodesForType, newEdges)
    }

    override fun recoverNetworkPartition(a: Int, b: Int): NodeCrashInfo {
        val newEdges = edges.map { it.toMutableSet() }
        newEdges[a].add(b)
        newEdges[b].add(a)
        val component = findMaxComponent(newEdges)
        val newFailedNodes = numberOfNodes - component.size
        return NodeCrashInfoSingle(testCfg, context, newFailedNodes, failedNodes, failedNodesForType, newEdges)
    }
}