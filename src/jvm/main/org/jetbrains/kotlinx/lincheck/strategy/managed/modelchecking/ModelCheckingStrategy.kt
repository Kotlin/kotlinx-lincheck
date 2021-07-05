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
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import kotlin.random.*

internal interface Interleaving {
    fun initialize()
}

internal interface InterleavingBuilder<out T : Interleaving> {
    val numberOfEvents: Int
    fun build(): T
}

/**
 * The model checking strategy studies all possible interleavings by increasing the
 * interleaving tree depth -- the number of context switches performed by the strategy.
 *
 * To restrict the number of interleaving to be studied, it is specified in [testCfg].
 * The strategy constructs an interleaving tree, where nodes choose where the next
 * context switch should be performed and to which thread.
 *
 * The strategy does not study the same interleaving twice.
 * The depth of the interleaving tree increases gradually when all possible
 * interleavings of the previous depth are studied. On the current level,
 * the interleavings are studied uniformly, to study as many different ones
 * as possible when the maximal number of interleavings to be studied is lower
 * than the number of all possible interleavings on the current depth level.
 */
internal abstract class AbstractModelCheckingStrategy<INTERLEAVING, BUILDER>(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier,
    recoverModel: RecoverabilityModel
) : ManagedStrategy(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg, recoverModel)
    where INTERLEAVING : AbstractModelCheckingStrategy<INTERLEAVING, BUILDER>.AbstractSwitchesInterleaving,
          BUILDER : InterleavingBuilder<INTERLEAVING>,
          BUILDER : AbstractModelCheckingStrategy<INTERLEAVING, BUILDER>.AbstractSwitchesInterleavingBuilder {
    // The number of invocations that the strategy is eligible to use to search for an incorrect execution.
    private val maxInvocations = testCfg.invocationsPerIteration
    // The number of already used invocations.
    private var usedInvocations = 0
    // The maximum number of thread switch choices that strategy should perform
    // (increases when all the interleavings with the current depth are studied).
    protected var maxNumberOfEvents = 0
    // The root of the interleaving tree that chooses the starting thread.
    private var root: InterleavingTreeNode = createRoot()
    // This random is used for choosing the next unexplored interleaving node in the tree.
    private val generationRandom = Random(0)
    // The interleaving that will be studied on the next invocation.
    protected lateinit var currentInterleaving: INTERLEAVING

    protected abstract fun createBuilder(): BUILDER
    protected open fun createRoot(): InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())

    override fun runImpl(): LincheckFailure? {
        while (usedInvocations < maxInvocations) {
            // get new unexplored interleaving
            currentInterleaving = root.nextInterleaving() ?: break
            usedInvocations++
            // run invocation and check its results
            checkResult(runInvocation())?.let { return it }
        }
        return null
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        if (mustSwitch) {
            // Create new execution position if this is a forced switch.
            // All other execution positions are covered by `shouldSwitch` method,
            // but forced switches do not ask `shouldSwitch`, because they are forced.
            // a choice of this execution position will mean that the next switch is the forced one.
            currentInterleaving.newExecutionPosition(iThread)
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // Crete a new current position in the same place as where the check is,
        // because the position check and the position increment are dual operations.
        check(iThread == currentThread)
        currentInterleaving.newExecutionPosition(iThread)
        return currentInterleaving.isSwitchPosition()
    }

    override fun initializeInvocation() {
        currentInterleaving.initialize()
        super.initializeInvocation()
    }

    override fun chooseThread(iThread: Int): Int = currentInterleaving.chooseThread(iThread)

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    abstract inner class InterleavingTreeNode {
        private var fractionUnexplored = 1.0
        lateinit var choices: List<Choice>
        var isFullyExplored: Boolean = false
            protected set
        val isInitialized get() = ::choices.isInitialized

        fun nextInterleaving(): INTERLEAVING? {
            if (isFullyExplored) {
                // Increase the maximum number of switches that can be used,
                // because there are no more not covered interleavings
                // with the previous maximum number of switches.
                maxNumberOfEvents++
                resetExploration()
            }
            // Check if everything is fully explored and there are no possible interleavings with more switches.
            if (isFullyExplored) return null
            return nextInterleaving(createBuilder())
        }

        abstract fun nextInterleaving(interleavingBuilder: BUILDER): INTERLEAVING

        protected fun resetExploration() {
            if (!isInitialized) {
                // This is a leaf node.
                isFullyExplored = false
                fractionUnexplored = 1.0
                return
            }
            choices.forEach { it.node.resetExploration() }
            updateExplorationStatistics()
        }

        fun finishExploration() {
            isFullyExplored = true
            fractionUnexplored = 0.0
        }

        protected fun updateExplorationStatistics() {
            check(isInitialized) { "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)" }
            if (choices.isEmpty()) {
                finishExploration()
                return
            }
            val total = choices.fold(0.0) { acc, choice ->
                acc + choice.node.fractionUnexplored
            }
            fractionUnexplored = total / choices.size
            isFullyExplored = choices.all { it.node.isFullyExplored }
        }

        protected fun chooseUnexploredNode(): Choice {
            if (choices.size == 1) return choices.first()
            // Choose a weighted random child.
            val total = choices.sumByDouble { it.node.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEach { choice ->
                sumWeight += choice.node.fractionUnexplored
                if (sumWeight >= random)
                    return choice
            }
            // In case of errors because of floating point numbers choose the last unexplored choice.
            return choices.last { !it.node.isFullyExplored }
        }
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    protected open inner class ThreadChoosingNode(switchableThreads: List<Int>) : InterleavingTreeNode() {
        init {
            choices = switchableThreads.map { Choice(SwitchChoosingNode(), it) }
        }

        override fun nextInterleaving(interleavingBuilder: BUILDER): INTERLEAVING {
            val child = chooseUnexploredNode()
            interleavingBuilder.addThreadSwitchChoice(child.value)
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    internal inner class SwitchChoosingNode : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: BUILDER): INTERLEAVING {
            val isLeaf = maxNumberOfEvents == interleavingBuilder.numberOfEvents
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNoninitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addSwitchPosition(choice.value)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    inner class Choice(val node: InterleavingTreeNode, val value: Int)

    /**
     * This class specifies an interleaving that is re-producible.
     */
    abstract inner class AbstractSwitchesInterleaving(
        protected val switchPositions: List<Int>,
        private val threadSwitchChoices: List<Int>,
        protected var lastNotInitializedNode: InterleavingTreeNode?
    ) : Interleaving {
        private lateinit var interleavingFinishingRandom: Random
        private lateinit var nextThreadToSwitch: Iterator<Int>
        protected var lastNotInitializedNodeChoices: MutableList<Choice>? = null
        protected var executionPosition: Int = 0

        override fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with a constant seed
            nextThreadToSwitch = threadSwitchChoices.iterator()
            currentThread = nextThreadToSwitch.next() // choose initial executing thread
            lastNotInitializedNodeChoices = null
            lastNotInitializedNode?.let {
                // Create a mutable list for the initialization of the not initialized node choices.
                lastNotInitializedNodeChoices = mutableListOf<Choice>().also { choices ->
                    it.choices = choices
                }
                lastNotInitializedNode = null
            }
        }

        fun chooseThread(iThread: Int): Int =
            if (nextThreadToSwitch.hasNext()) {
                // Use the predefined choice.
                val result = nextThreadToSwitch.next()
                check(result in switchableThreads(iThread))
                result
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.

                // end of execution position choosing initialization because of new switch
                lastNotInitializedNodeChoices = null
                switchableThreads(iThread).random(interleavingFinishingRandom)
            }

        fun isSwitchPosition() = executionPosition in switchPositions

        /**
         * Creates a new execution position that corresponds to the current switch point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        open fun newExecutionPosition(iThread: Int) {
            executionPosition++
            if (executionPosition > lastChosenExecutionPosition()) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                val choices = lastNotInitializedNodeChoices ?: return
                val choice = Choice(ThreadChoosingNode(switchableThreads(iThread)), executionPosition)
                choices.add(choice)
            }
        }

        private fun lastChosenExecutionPosition() = switchPositions.lastOrNull() ?: -1
    }

    abstract inner class AbstractSwitchesInterleavingBuilder : InterleavingBuilder<INTERLEAVING> {
        protected val switchPositions = mutableListOf<Int>()
        protected val threadSwitchChoices = mutableListOf<Int>()
        protected var lastNoninitializedNode: InterleavingTreeNode? = null

        override val numberOfEvents get() = switchPositions.size

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun addLastNoninitializedNode(lastNoninitializedNode: InterleavingTreeNode) {
            this.lastNoninitializedNode = lastNoninitializedNode
        }
    }
}

internal class ModelCheckingStrategy(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier,
    recoverModel: RecoverabilityModel
) : AbstractModelCheckingStrategy<ModelCheckingStrategy.SwitchesInterleaving, ModelCheckingStrategy.SwitchesInterleavingBuilder>(
    testCfg, testClass, scenario, validationFunctions, stateRepresentation, verifier, recoverModel
) {
    override fun createBuilder() = SwitchesInterleavingBuilder()

    internal inner class SwitchesInterleavingBuilder : AbstractSwitchesInterleavingBuilder() {
        override fun build() = SwitchesInterleaving(switchPositions, threadSwitchChoices, lastNoninitializedNode)
    }

    internal inner class SwitchesInterleaving(
        switchPositions: List<Int>,
        threadSwitchChoices: List<Int>,
        lastNotInitializedNode: InterleavingTreeNode?
    ) : AbstractSwitchesInterleaving(switchPositions, threadSwitchChoices, lastNotInitializedNode)
}
