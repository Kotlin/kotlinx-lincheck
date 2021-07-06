package org.jetbrains.kotlinx.lincheck.strategy.stress;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.nvm.Recover;
import org.jetbrains.kotlinx.lincheck.verifier.DummySequentialSpecification;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;

import java.lang.annotation.*;

/**
 * This annotation configures concurrent test using stress strategy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(StressCTest.StressCTests.class)
@Inherited
public @interface StressCTest {
    /**
     * The number of different test scenarios to be executed
     */
    int iterations() default CTestConfiguration.DEFAULT_ITERATIONS;

    /**
     * Run each test scenario {@code invocations} times.
     */
    int invocationsPerIteration() default StressCTestConfiguration.DEFAULT_INVOCATIONS;

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
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    Class<?> sequentialSpecification() default DummySequentialSpecification.class;

    Recover recover() default Recover.NO_RECOVER;

    /**
     * Holder annotation for {@link StressCTest}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface StressCTests {
        StressCTest[] value();
    }
}

