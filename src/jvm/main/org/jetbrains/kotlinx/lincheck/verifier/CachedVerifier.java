package org.jetbrains.kotlinx.lincheck.verifier;

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

import org.jetbrains.kotlinx.lincheck.execution.*;

import java.util.*;

/**
 * This verifier cached the already verified results in a hash table,
 * and look into this hash table at first. In case of many invocations
 * with the same scenario, this optimization improves the verification
 * phase significantly.
 */
public abstract class CachedVerifier implements Verifier {
    private final Map<ExecutionScenario, Set<ExecutionResult>> previousResults = new WeakHashMap<>();

    @Override
    public boolean verifyResults(ExecutionScenario scenario, ExecutionResult results) {
        // Stacktrace is a large object, that is useless for verification, as it is used only for report.
        ExecutionResult resultWithoutCrashes = ExecutionResultKt.getWithoutCrashes(results);
        boolean newResult = previousResults.computeIfAbsent(scenario, s -> new HashSet<>()).add(resultWithoutCrashes);
        if (!newResult) return true;
        return verifyResultsImpl(scenario, results);
    }

    public abstract boolean verifyResultsImpl(ExecutionScenario scenario, ExecutionResult results);
}
