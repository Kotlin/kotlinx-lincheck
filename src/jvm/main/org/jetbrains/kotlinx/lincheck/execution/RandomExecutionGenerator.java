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

import org.jetbrains.kotlinx.lincheck.Actor;
import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.CTestStructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class RandomExecutionGenerator extends ExecutionGenerator {
    private final Random random = new Random(0);

    public RandomExecutionGenerator(CTestConfiguration testConfiguration, CTestStructure testStructure) {
        super(testConfiguration, testStructure);
    }

    @Override
    public ExecutionScenario nextExecution() {
        int operationId = 0;
        // Create init execution part
        List<ActorGenerator> validActorGeneratorsForInit = testStructure.actorGenerators.stream()
            .filter(ag -> !ag.getUseOnce() && !ag.isSuspendable()).collect(Collectors.toList());
        List<Actor> initExecution = new ArrayList<>();
        for (int i = 0; i < testConfiguration.getActorsBefore() && !validActorGeneratorsForInit.isEmpty(); i++) {
            ActorGenerator ag = validActorGeneratorsForInit.get(random.nextInt(validActorGeneratorsForInit.size()));
            initExecution.add(ag.generate(0, operationId++));
        }
        // Create parallel execution part
        // Construct non-parallel groups and parallel one
        List<CTestStructure.OperationGroup> nonParallelGroups = testStructure.operationGroups.stream()
            .filter(g -> g.nonParallel)
            .collect(Collectors.toList());
        Collections.shuffle(nonParallelGroups);
        List<ActorGenerator> parallelGroup = new ArrayList<>(testStructure.actorGenerators);
        nonParallelGroups.forEach(g -> parallelGroup.removeAll(g.actors));

        List<List<Actor>> parallelExecution = new ArrayList<>();
        List<ThreadGen> threadGens = new ArrayList<>();
        for (int t = 0; t < testConfiguration.getThreads(); t++) {
            parallelExecution.add(new ArrayList<>());
            threadGens.add(new ThreadGen(t, testConfiguration.getActorsPerThread()));
        }
        for (int i = 0; i < nonParallelGroups.size(); i++) {
            threadGens.get(i % threadGens.size()).nonParallelActorGenerators
                .addAll(nonParallelGroups.get(i).actors);
        }
        List<ThreadGen> tgs2 = new ArrayList<>(threadGens);
        while (!threadGens.isEmpty()) {
            for (Iterator<ThreadGen> it = threadGens.iterator(); it.hasNext(); ) {
                ThreadGen threadGen = it.next();
                int aGenIndexBound = threadGen.nonParallelActorGenerators.size() + parallelGroup.size();
                if (aGenIndexBound == 0) {
                    it.remove();
                    continue;
                }
                int aGenIndex = random.nextInt(aGenIndexBound);
                ActorGenerator agen;
                if (aGenIndex < threadGen.nonParallelActorGenerators.size()) {
                    agen = getActorGenFromGroup(threadGen.nonParallelActorGenerators, aGenIndex);
                } else {
                    agen = getActorGenFromGroup(parallelGroup,
                        aGenIndex - threadGen.nonParallelActorGenerators.size());
                }
                parallelExecution.get(threadGen.iThread).add(agen.generate(threadGen.iThread + 1, operationId++));
                if (--threadGen.left == 0)
                    it.remove();
            }
        }
        parallelExecution = parallelExecution.stream().filter(actors -> !actors.isEmpty()).collect(Collectors.toList());
        // Create post execution part if the parallel part does not have suspendable actors
        List<Actor> postExecution;
        if (parallelExecution.stream().noneMatch(actors -> actors.stream().anyMatch(Actor::isSuspendable))) {
            postExecution = new ArrayList<>();
            List<ActorGenerator> leftActorGenerators = new ArrayList<>(parallelGroup);
            for (ThreadGen threadGen : tgs2)
                leftActorGenerators.addAll(threadGen.nonParallelActorGenerators);
            for (int i = 0; i < testConfiguration.getActorsAfter() && !leftActorGenerators.isEmpty(); i++) {
                ActorGenerator agen = getActorGenFromGroup(leftActorGenerators, random.nextInt(leftActorGenerators.size()));
                postExecution.add(agen.generate(testConfiguration.getThreads() + 1, operationId++));
            }
        } else {
            postExecution = Collections.emptyList();
        }
        return new ExecutionScenario(initExecution, parallelExecution, postExecution);
    }

    private ActorGenerator getActorGenFromGroup(List<ActorGenerator> aGens, int index) {
        ActorGenerator aGen = aGens.get(index);
        if (aGen.getUseOnce())
            aGens.remove(index);
        return aGen;
    }

    private static class ThreadGen {
        final List<ActorGenerator> nonParallelActorGenerators = new ArrayList<>();
        int iThread;
        int left;

        ThreadGen(int iThread, int nActors) {
            this.iThread = iThread;
            this.left = nActors;
        }
    }
}