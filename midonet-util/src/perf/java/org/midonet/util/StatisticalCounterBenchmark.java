/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(5)
@Threads(Threads.MAX)
@State(Scope.Benchmark)
public class StatisticalCounterBenchmark {
    final static AtomicInteger THREAD_INDEX = new AtomicInteger(0);

    final StatisticalCounter counter;
    {
        int processors = Runtime.getRuntime().availableProcessors();
        counter = new StatisticalCounter(processors);
    }

    @State(Scope.Thread)
    public static class ThreadIndex {
        public int value = THREAD_INDEX.getAndIncrement();
    }

    // Measure all threads doing normal additions
    @Benchmark
    public long measureNormal(ThreadIndex index) {
        return counter.addAndGet(index.value, 1);
    }

    // Measure all threads doing uncontended atomic additions
    @Benchmark
    public long measureAtomic(ThreadIndex index) {
        return counter.addAndGetAtomic(index.value, 1);
    }

    // Measure our realistic scenario where we can have more than
    // one thread accessing a single slot concurrently if Midolman
    // is configured with the one_to_one threading model.
    @State(Scope.Group)
    public static class GroupIndex {
        public int value = THREAD_INDEX.getAndIncrement();
    }

    @Benchmark
    @Group("realistic")
    public long measureRealistic(ThreadIndex index) {
        return counter.addAndGet(index.value, 1);
    }

    @Benchmark
    @Group("realistic")
    public long measureSpecialIndex(GroupIndex index) {
        long result = counter.addAndGetAtomic(index.value, 1);
        Blackhole.consumeCPU(1); // back-off
        return result;
    }
}
