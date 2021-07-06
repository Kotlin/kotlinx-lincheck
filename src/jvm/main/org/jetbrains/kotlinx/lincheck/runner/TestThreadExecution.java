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
package org.jetbrains.kotlinx.lincheck.runner;

import org.jetbrains.kotlinx.lincheck.Result;


/**
 * Instance of this class represents the test execution for ONE thread. Several instances should be ran in parallel.
 * All implementations of this class should be generated via {@link TestThreadExecutionGenerator}.
 * The results and clocks are in the `results`  and `clocks` fields correspondingly.
 *
 * <p> This class should be public for having access from generated ones.
 */
public abstract class TestThreadExecution implements Runnable {
    // The following fields are assigned in TestThreadExecutionGenerator
    protected Runner runner;
    public Object testInstance;
    protected Object[] objArgs;
    public TestThreadExecution[] allThreadExecutions;

    public Result[] results; // for ExecutionResult
    public int[][] clocks; // for HBClock
    public volatile int curClock;
    public boolean useClocks;
    public boolean useClocksOnce = false;

    public void readClocks(int currentActor) {
        for (int i = 0; i < allThreadExecutions.length; i++) {
            clocks[currentActor][i] = allThreadExecutions[i].curClock;
        }
    }

    public void incClock() {
        curClock++;
    }

    public void useClocksOnce() {
        useClocksOnce = true;
    }

    public void resetUseClocksOnce() {
        useClocksOnce = false;
    }
}