package org.jetbrains.kotlinx.lincheck.paramgen;

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

import java.util.Random;

public class DoubleGen implements ParameterGenerator<Double> {
    private static final float DEFAULT_BEGIN = -10;
    private static final float DEFAULT_END = 10;
    private static final float DEFAULT_STEP = 0.1f;

    private final Random random = new Random(0);
    private final double begin;
    private final double end;
    private final double step;

    public DoubleGen(String configuration) {
        if (configuration.isEmpty()) { // use default configuration
            begin = DEFAULT_BEGIN;
            end = DEFAULT_END;
            step = DEFAULT_STEP;
            return;
        }
        String[] args = configuration.replaceAll("\\s", "").split(":");
        switch (args.length) {
        case 2: // begin:end
            begin = Double.parseDouble(args[0]);
            end = Double.parseDouble(args[1]);
            step = DEFAULT_STEP;
            break;
        case 3: // begin:step:end
            begin = Double.parseDouble(args[0]);
            step = Double.parseDouble(args[1]);
            end = Double.parseDouble(args[2]);
            break;
        default:
            throw new IllegalArgumentException("Configuration should have two (begin and end) " +
                "or three (begin, step and end) arguments  separated by colon");
        }
        if ((end - begin) / step >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("step is too small for specified range");
    }

    public Double generate() {
        double delta = end - begin;
        if (step == 0) // step is not defined
            return begin + delta * random.nextDouble();
        int maxSteps = (int) (delta / step);
        return begin + delta * random.nextInt(maxSteps + 1);
    }
}
