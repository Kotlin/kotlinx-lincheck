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
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking;

import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.nvm.Recover;
import org.jetbrains.kotlinx.lincheck.verifier.*;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*;

import java.lang.annotation.*;

import static org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.*;

/**
 * This annotation configures concurrent test using {@link ModelCheckingStrategy managed} strategy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ModelCheckingCTest.ModelCheckingCTests.class)
@Inherited
public @interface ModelCheckingCTest {
    /**
     * The number of different test scenarios to be executed
     */
    int iterations() default CTestConfiguration.DEFAULT_ITERATIONS;

    /**
     * Use the specified number of threads for the parallel part of an execution.
     * <p>
     * Note, that the the actual number of threads can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#parallelExecution
     */
    int threads() default CTestConfiguration.DEFAULT_THREADS;

    /**
     * Generate the specified number of operations for each thread of the parallel part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#parallelExecution
     */
    int actorsPerThread() default CTestConfiguration.DEFAULT_ACTORS_PER_THREAD;

    /**
     * Generate the specified number of operation for the initial sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#initExecution
     */
    int actorsBefore() default CTestConfiguration.DEFAULT_ACTORS_BEFORE;

    /**
     * Generate the specified number of operation for the last sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#postExecution
     */
    int actorsAfter() default CTestConfiguration.DEFAULT_ACTORS_AFTER;

    /**
     * Use the specified execution generator.
     */
    Class<? extends ExecutionGenerator> generator() default RandomExecutionGenerator.class;

    /**
     * Use the specified verifier.
     */
    Class<? extends Verifier> verifier() default LinearizabilityVerifier.class;

    /**
     * Check the testing concurrent algorithm for obstruction freedom.
     */
    boolean checkObstructionFreedom() default false;

    /**
     * Use the specified maximum number of the same event repetitions to detect "hangs".
     * In this case, the strategy is either forced to switch the current thread, or report
     * the obstruction-freedom violation if {@link ModelCheckingCTest#checkObstructionFreedom} is enabled.
     */
    int hangingDetectionThreshold() default DEFAULT_HANGING_DETECTION_THRESHOLD;

    /**
     * The maximal number of invocations that the managed strategy can use to search for finding an incorrect execution.
     * It is also possible that the strategy explores all the possible interleavings with fewer invocations.
     */
    int invocationsPerIteration() default DEFAULT_INVOCATIONS;

    /**
     * Require correctness check of test instance state equivalency relation, which is defined by the user.
     * Essentially, it checks whether two new instances of the test class are equal.
     * If the check fails, an {@link IllegalStateException} is thrown.
     */
    boolean requireStateEquivalenceImplCheck() default false;

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    boolean minimizeFailedScenario() default true;

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by {@link Verifier} to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     * However, some verifiers require additional parameters for these methods.
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    Class<?> sequentialSpecification() default DummySequentialSpecification.class;

    Recover recover() default Recover.NO_RECOVER;

    /**
     * Holder annotation for {@link ModelCheckingCTest}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface ModelCheckingCTests {
        ModelCheckingCTest[] value();
    }
}