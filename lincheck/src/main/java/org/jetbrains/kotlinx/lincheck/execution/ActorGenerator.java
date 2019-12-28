package org.jetbrains.kotlinx.lincheck.execution;

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

import kotlin.random.Random;
import org.jetbrains.kotlinx.lincheck.Actor;

import org.jetbrains.kotlinx.lincheck.ActorKt;
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementations of this class generate {@link Actor actors}
 * using {@link ParameterGenerator parameter generators}.
 */
public class ActorGenerator {
    private final Method method;
    private final List<ParameterGenerator<?>> parameterGenerators;
    private final List<Class<? extends Throwable>> handledExceptions;
    private final boolean useOnce;
    private final boolean cancellableOnSuspension;

    public ActorGenerator(Method method, List<ParameterGenerator<?>> parameterGenerators,
        List<Class<? extends Throwable>> handledExceptions, boolean useOnce, boolean cancellableOnSuspension)
    {
        this.method = method;
        this.parameterGenerators = parameterGenerators;
        this.handledExceptions = handledExceptions;
        this.useOnce = useOnce;
        this.cancellableOnSuspension = cancellableOnSuspension && isSuspendable();
    }

    public Actor generate() {
        List<Object> parameters = parameterGenerators.stream().map(ParameterGenerator::generate).collect(Collectors.toList());
        return new Actor(method, parameters, handledExceptions, cancellableOnSuspension & Random.Default.nextBoolean());
    }

    public void reset() {
        parameterGenerators.forEach(ParameterGenerator::reset);
    }

    public boolean useOnce() {
        return useOnce;
    }

    public boolean isSuspendable() {
        return ActorKt.isSuspendable(method);
    }

    @Override
    public String toString() {
        return method.toString();
    }
}