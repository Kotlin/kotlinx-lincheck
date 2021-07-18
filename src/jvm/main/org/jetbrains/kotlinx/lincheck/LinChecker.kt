/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.stress.Probability
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.*

/**
 * This class runs concurrent tests.
 */
class LinChecker(private val testClass: Class<*>, options: Options<*, *>?) {
    private val testStructure = CTestStructure.getFromTestClass(testClass)
    private val testConfigurations: List<CTestConfiguration>
    private val reporter: Reporter

    init {
        val logLevel = options?.logLevel ?: testClass.getAnnotation(LogLevel::class.java)?.value ?: DEFAULT_LOG_LEVEL
        reporter = Reporter(logLevel)
        testConfigurations = if (options != null) listOf(options.createTestConfigurations(testClass))
        else createFromTestClassAnnotations(testClass)
    }

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        val failure = checkImpl() ?: return
        throw LincheckAssertionError(failure)
    }

    /**
     * @return TestReport with information about concurrent test run.
     */
    internal fun checkImpl(): LincheckFailure? {
        check(testConfigurations.isNotEmpty()) { "No Lincheck test configuration to run" }
        for (testCfg in testConfigurations) {
            val failure = testCfg.checkImpl()
            if (failure != null) return failure
        }
        return null
    }

    private fun CTestConfiguration.checkImpl(): LincheckFailure? {
        val exGen = createExecutionGenerator()
        val verifier = createVerifier(checkStateEquivalence = true)
        for (i in customScenarios.indices) {
            val scenario = customScenarios[i]
            scenario.validate()
            reporter.logIteration(i + 1, customScenarios.size, scenario)
            val failure = scenario.run(this, verifier)
            if (failure != null) return failure
        }
        repeat(iterations) { i ->
            val scenario = exGen.nextExecution()
            scenario.validate()
            reporter.logIteration(i + 1 + customScenarios.size, iterations, scenario)
            val failure = scenario.run(this, verifier)
            if (failure != null) {
                val minimizedFailedIteration = if (!minimizeFailedScenario) failure
                else failure.minimize(this)
                reporter.logFailedIteration(minimizedFailedIteration)
                return minimizedFailedIteration
            }
        }
        return null
    }

    // Tries to minimize the specified failing scenario to make the error easier to understand.
    // The algorithm is greedy: it tries to remove one actor from the scenario and checks
    // whether a test with the modified one fails with error as well. If it fails,
    // then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
    // Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
    // Thus, the algorithm works in the linear time of the total number of actors.
    private fun LincheckFailure.minimize(testCfg: CTestConfiguration): LincheckFailure {
        reporter.logScenarioMinimization(scenario)
        var minimizedFailure = this
        while (true) {
            minimizedFailure = minimizedFailure.scenario.tryMinimize(testCfg) ?: break
        }
        if (testCfg is DistributedCTestConfiguration<*, *>) {
            return minimizedFailure.minimizeDSFailure(testCfg)
        }
        return minimizedFailure
    }

    private fun ExecutionScenario.tryMinimize(testCfg: CTestConfiguration): LincheckFailure? {
        // Reversed indices to avoid conflicts with in-loop removals
        for (i in parallelExecution.indices.reversed()) {
            for (j in parallelExecution[i].indices.reversed()) {
                val failure = tryMinimize(i + 1, j, testCfg)
                if (failure != null) return failure
            }
        }
        for (j in initExecution.indices.reversed()) {
            val failure = tryMinimize(0, j, testCfg)
            if (failure != null) return failure
        }
        for (j in postExecution.indices.reversed()) {
            val failure = tryMinimize(threads + 1, j, testCfg)
            if (failure != null) return failure
        }

        return null
    }

    private fun ExecutionScenario.tryMinimize(
        threadId: Int,
        position: Int,
        testCfg: CTestConfiguration
    ): LincheckFailure? {
        val newScenario = this.copy()
        val actors = newScenario[threadId] as MutableList<Actor>
        actors.removeAt(position)
        if (actors.isEmpty() && threadId != 0 && threadId != newScenario.threads + 1) {
            // Also remove the empty thread
            newScenario.parallelExecution.removeAt(threadId - 1)
        }
        return if (newScenario.isValid) {
            val verifier = testCfg.createVerifier(checkStateEquivalence = false)
            newScenario.run(testCfg, verifier)
        } else null
    }

    private fun LincheckFailure.minimizeDSFailure(testCfg: DistributedCTestConfiguration<*, *>): LincheckFailure {
        val queue = LinkedList(testCfg.nextConfigurations())
        var res = this
        while (queue.size != 0) {
            val nextTestCfg = queue.poll()
            val newFailedIteration = res.scenario.tryMinimize(nextTestCfg)
            if (newFailedIteration != null) {
                res = newFailedIteration
                queue.addAll(nextTestCfg.nextConfigurations())
            }
        }
        while (Probability.failedNodesExpectation > 0) {
            Probability.failedNodesExpectation--
            val newFailedIteration = res.scenario.tryMinimize(testCfg)
            if (newFailedIteration != null) {
                res = newFailedIteration
            } else {
                Probability.failedNodesExpectation = -1
                return res
            }
        }
        Probability.failedNodesExpectation = -1
        return res
    }

    private fun ExecutionScenario.run(testCfg: CTestConfiguration, verifier: Verifier): LincheckFailure? =
        testCfg.createStrategy(
            testClass = testClass,
            scenario = this,
            validationFunctions = testStructure.validationFunctions,
            stateRepresentationMethod = testStructure.stateRepresentation,
            verifier = verifier
        ).run()

    private fun ExecutionScenario.copy() = ExecutionScenario(
        ArrayList(initExecution),
        parallelExecution.map { ArrayList(it) },
        ArrayList(postExecution)
    )

    private val ExecutionScenario.isValid: Boolean
        get() = !isParallelPartEmpty &&
                (!hasSuspendableActors() || (!hasSuspendableActorsInInitPart && !hasPostPartAndSuspendableActors))

    private fun ExecutionScenario.validate() {
        require(!isParallelPartEmpty) {
            "The generated scenario has empty parallel part"
        }
        if (hasSuspendableActors()) {
            require(!hasSuspendableActorsInInitPart) {
                "The generated scenario for the test class with suspendable methods contains suspendable actors in initial part"
            }
            require(!hasPostPartAndSuspendableActors) {
                "The generated scenario  for the test class with suspendable methods has non-empty post part"
            }
        }
    }

    private val ExecutionScenario.hasSuspendableActorsInInitPart
        get() =
            initExecution.stream().anyMatch(Actor::isSuspendable)
    private val ExecutionScenario.hasPostPartAndSuspendableActors
        get() =
            (parallelExecution.stream()
                .anyMatch { actors -> actors.stream().anyMatch { it.isSuspendable } } && postExecution.size > 0)
    private val ExecutionScenario.isParallelPartEmpty
        get() =
            parallelExecution.map { it.size }.sum() == 0


    private fun CTestConfiguration.createVerifier(checkStateEquivalence: Boolean) =
        verifierClass.getConstructor(Class::class.java).newInstance(sequentialSpecification).also {
            if (!checkStateEquivalence) return@also
            val stateEquivalenceCorrect = it.checkStateEquivalenceImplementation()
            if (!stateEquivalenceCorrect) {
                if (requireStateEquivalenceImplCheck) {
                    val errorMessage =
                        StringBuilder().appendStateEquivalenceViolationMessage(sequentialSpecification).toString()
                    error(errorMessage)
                } else {
                    reporter.logStateEquivalenceViolation(sequentialSpecification)
                }
            }
        }

    private fun CTestConfiguration.createExecutionGenerator() =
        generatorClass.getConstructor(
            CTestConfiguration::class.java,
            CTestStructure::class.java
        ).newInstance(this, testStructure)

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: Options<*, *>? = null) {
            LinChecker(testClass, options).check()
        }
    }
}


/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: Class<*>) = LinChecker.check(testClass, this)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: KClass<*>) = this.check(testClass.java)

internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>) = LinChecker(testClass, this).checkImpl()