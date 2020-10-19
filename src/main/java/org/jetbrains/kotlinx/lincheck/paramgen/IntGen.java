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

public class IntGen implements ParameterGenerator<Integer> {
    private static final int DEFAULT_BEGIN = -10;
    private static final int DEFAULT_END = 10;

    private final Random random = new Random(0);
    private final int begin;
    private final int end;

    public IntGen(String configuration) {
        if (configuration.isEmpty()) { // use default configuration
            begin = DEFAULT_BEGIN;
            end = DEFAULT_END;
            return;
        }
        String[] args = configuration.replaceAll("\\s", "").split(":");
        switch (args.length) {
        case 2: // begin:end
            begin = Integer.parseInt(args[0]);
            end = Integer.parseInt(args[1]);
            break;
        default:
            throw new IllegalArgumentException("Configuration should have " +
                "two arguments (begin and end) separated by colon");
        }
    }

    public Integer generate() {
        return begin + random.nextInt(end - begin + 1);
    }

    void checkRange(int min, int max, String type) {
        if (this.begin < min || this.end - 1 > max) {
            throw new IllegalArgumentException("Illegal range for "
                + type + " type: [" + begin + "; " + end + ")");
        }
    }
}
