package org.jetbrains.kotlinx.lincheck;

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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.randomswitch.RandomSwitchCTest;
import org.jetbrains.kotlinx.lincheck.strategy.randomswitch.RandomSwitchCTestConfiguration;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration;
import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativelyRelaxedLinearizabilityVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration of an abstract concurrent test.
 * Should be overridden for every strategy.
 */
public abstract class CTestConfiguration {
    public static final int DEFAULT_ITERATIONS = 200;
    public static final int DEFAULT_THREADS = 2;
    public static final int DEFAULT_ACTORS_PER_THREAD = 5;
    public static final int DEFAULT_ACTORS_BEFORE = 5;
    public static final int DEFAULT_ACTORS_AFTER = 5;
    public static final Class<? extends ExecutionGenerator> DEFAULT_EXECUTION_GENERATOR = RandomExecutionGenerator.class;
    public static final Class<? extends Verifier> DEFAULT_VERIFIER = QuantitativelyRelaxedLinearizabilityVerifier.class;

    public final int iterations;
    public final int threads;
    public final int actorsPerThread;
    public final int actorsBefore;
    public final int actorsAfter;
    public final Class<? extends ExecutionGenerator> generatorClass;
    public final Class<? extends Verifier> verifierClass;
    public boolean hasTestClassSuspendableActors;

    protected CTestConfiguration(int iterations, int threads, int actorsPerThread, int actorsBefore, int actorsAfter,
        Class<? extends ExecutionGenerator> generatorClass, Class<? extends Verifier> verifierClass)
    {
        this.iterations = iterations;
        this.threads = threads;
        this.actorsPerThread = actorsPerThread;
        this.actorsBefore = actorsBefore;
        this.actorsAfter = actorsAfter;
        this.generatorClass = generatorClass;
        this.verifierClass = verifierClass;
    }

    static List<CTestConfiguration> createFromTestClass(Class<?> testClass) {
        boolean hasTestClassSuspendableActors = Arrays.stream(testClass.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(Operation.class) &&
                m.getParameterTypes().length > 0 && m.getParameterTypes()[m.getParameterTypes().length - 1].isAssignableFrom(kotlin.coroutines.Continuation.class));
        Stream<StressCTestConfiguration> stressConfigurations = Arrays.stream(testClass.getAnnotationsByType(StressCTest.class))
            .map(ann -> {
                if (hasTestClassSuspendableActors && ann.actorsAfter() > 0) {
                    throw new IllegalArgumentException("Post execution part is not allowed for test classes with suspendable operations");
                }
                return new StressCTestConfiguration(ann.iterations(),
                        ann.threads(), ann.actorsPerThread(), ann.actorsBefore(), ann.actorsAfter(),
                        ann.generator(), ann.verifier(), ann.invocationsPerIteration(), true);
            });
        Stream<RandomSwitchCTestConfiguration> randomSwitchConfigurations = Arrays.stream(testClass.getAnnotationsByType(RandomSwitchCTest.class))
            .map(ann -> {
                if (hasTestClassSuspendableActors && ann.actorsAfter() > 0) {
                    throw new IllegalArgumentException("Post execution part is not allowed for test classes with suspendable operations");
                }
                return new RandomSwitchCTestConfiguration(ann.iterations(),
                        ann.threads(), ann.actorsPerThread(), ann.actorsBefore(), ann.actorsAfter(),
                        ann.generator(), ann.verifier(), ann.invocationsPerIteration());
            });
        return Stream.concat(stressConfigurations, randomSwitchConfigurations).collect(Collectors.toList());
    }
}