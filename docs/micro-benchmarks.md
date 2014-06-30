# Micro-benchmarks

Micro-benchmarks are programs we write to discover some narrow targeted fact,
usually written as a timed tight loop around some computation. This contrasts
with macro-benchmarks, which are realistic, larger and longer-running, or load
testing.

Micro-benchmarks help us discover performance characteristics of chosen code
paths and inform our decisions regarding performance and memory optimizations.

## Pitfalls

Following are some common pitfalls when benchmarking code on a managed runtime
environment.

### Warm-up

Code running on the JVM starts out as being interpreted and only JIT'd later on.
JIT'd code is 10x faster than the interpreter. On a server VM, JIT'ing happens
after 10,000 iterations of that method (both method calls and loops inside it
count towards this threshold), plus the time it takes to compile the method.
The type's method table will be updated and the JIT'd code will be executed the
next time the method is invoked.

When benchmarking, we must first run enough warm-up iterations to ensure the
transient responses settle down, in particular that the code we intend to
benchmark is compiled when we start measuring. Note that JIT'ing code is not
the only online optimization, and they all require warm-up to take effect (e.g.,
biased locking has a configurable startup delay, defaulting to 4 seconds).

### Compile plan

The JIT makes inlining and other complex decisions driven by timer-based method
sampling, thread scheduling, garbage collection, and various system effects that
are very volatile and vary from run to run. Many algorithms are non-deterministic,
such as memory allocators, non-fair locks, concurrent data structures, etc. As a
result, performance varies with each new JVM launch.

Micro-benchmarks are particularly susceptible to this. When benchmarking it's
best to launch the JVM many times and average all the results.

### Virtual calls

For virtual calls, in particular interface calls (`invokeinterface`), the JIT
will try to devirtualize the call if the call site is monomorphic, meaning that
the target is consistently of the same type. The JIT will perform a guarded
inline.

If we want to benchmark two or more implementations of the same interface and
run these benchmarks sequentially, then the first will always execute faster:
when running the benchmark with the second implementation, the guard will fail,
the method will be de-optimized, re-compiled as either a virtual call or a
dispatch table.

To avoid this, the benchmark's warm-up loop must invoke all targets (i.e., bulk
warm-up). Running each benchmark in a forked JVM can also help.

### Synchronization

We must account for locking and coordination overhead. Running a single-threaded
benchmark on code that will likely be contented in production will yield
drastically different results: a non-contented lock is always cheaper, with
biased locking making it practically free.

We must ensure that multi-threaded code is benchmarked with different contention
scenarios, varying not only the amount of threads executing the benchmark but
possibly employ some adaptive back-off to better simulate realistic conditions.

### OSR

When a running method appears to not exit, but it's getting hot (because of
looping and back-branching), the JVM will not only JIT the code after 10,000
iterations, but after 14,000 it will do an on-stack replacement (OSR) of the
running method. This is not typically useful in large systems but appears in
benchmarks.

Because of the way the OSR code must be compiled to allow a jump to it
during the method's execution, it can prevent significant optimizations like
range check elimination or loop unrolling. Performance can be as bad as half
of that of the JIT'ed method.

To avoid OSR, warm-up runs should be more, but shorter. Assuming the benchmark
method contains a loop, during warm-up the number of iterations it does should
be small, but that method should be called more times (enough to trigger a
compilation).

### Dead-code

Another pitfall is dead-code elimination: the JIT compiler is smart enough to
deduce some computations are redundant and eliminate them completely. If that
eliminated part was part of our benchmarked code, we'll get very misleading
results.

### Time sharing

When oversubscribing the processors, the kernel will multiplex multiple threads
on top of one CPU. This affects measurements in the sense that elapsed time
doesn't correspond to computation time if a thread is preempted and parked.

This can lead to subtle problems depending how we structure the benchmark:

```java
public long measure() {
   long ops = 0;
   long realTime = 0;
   while(!isDone) {
      setup(); // skip this
      long time = System.nanoTime();
        work();
      realTime += (System.nanoTime() - time);
      ops++;
   }
   return ops/realTime;
}
```

Here we only time the amount of real work. Consider 4 threads running this method
on a single CPU. A possible timing is: a thread is scheduled by the OS, executes
`setup()`; is preempted; it is then rescheduled, executes the timestamps and the
`work()` method and increments the number of operations; is preempted. If all
threads follow this pattern, then the execution time of `work()` will be the
same for all. This means that we can add more and more threads and keep getting
better results, way past what the hardware could support.

This is a form of [Coordinated Omission](https://groups.google.com/forum/#!msg/mechanical-sympathy/icNZJejUHfE/BfDekfBEs_sJ).

### Steady state

A steady state is such that the amount of work done per iteration is (more or
less) the same. We want to be able to measure each of those iterations and
arrive at statistical relevant results for that particular amount of work.

Benchmarking an algorithm such as Fibonacci is not trivial, because it doesn't
arrive at a steady state:

```java
BigInteger n1 = ONE; BigInteger n2 = ZERO;

public BigInteger next() {
    BigInteger cur = n1.add(n2);
    n2 = n1; n1 = cur;
    return cur;
}
```

The time spent in each consecutive call of `next()` is always increasing,
because we are dealing with larger and larger operands in `BigInteger.add`.

There is no good solution for non-steady-state benchmarks.

## JMH

JMH is a new micro-benchmarking framework, first released in late 2013. Its
distinctive advantage over other frameworks is that it is developed by the same
people in Oracle who implement the JIT compiler.

A benchmark targets a particular method. Each benchmark is comprised of
iterations, which in turn are a group of invocations. JMH performs as many
iterations as it needs to obtain a statistical relevant result. We can configure
the number of iterations per benchmark, as well as the number of warm-up
iterations.

Following are JMH's features and how it helps us avoid the pitfalls above.

### Code sample

To write a JMH benchmark we just need to annotate a method with the `@Benchmark`
annotation.

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
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

    @Benchmark
    public long measure(ThreadIndex index) {
        return counter.addAndGet(index.value, 1);
    }
}
```

For some benchmarks it may be a good practice to include an empty benchmark
method so we get a baseline with the overhead the infrastructure bears on the
code we're measuring. There are no magical infrastructures which incur no
overhead, and it's important to know what these are.

### Benchmark modes

JMH can measure the methods in different ways. We can choose between the
following modes by using the `@BenchmarkMode` annotation on the test methods:

* `Mode.Throughput`
    * Calculates number of operations in a time unit.
* `Mode.AverageTime`
    * Calculates the average running time of the operation; this is the
      reciprocal of the throughput.
* `Mode.SampleTime`
    * Calculates how long it takes for a method to run (including percentiles).
* `Mode.SingleShotTime`
    * Runs a method just once, useful for cold-testing. (It can be run more than
      once if we specify a batch size for the iterations, in which case JMH will
      calculate the batch running time.)
* Any set of these
    * The test will be run as many times as the modes specified.
* Mode.All
    * All these modes one after another.

### Time units

The `@OutputTimeUnit` attribute receives a `java.util.concurrent.TimeUnit` and
defines what is the time-unit the benchmark will use. This time-unit applies to
all modes of the benchmark (which is unfortunate, because we could want to
measure throughput in seconds but average time in nanoseconds).

### Benchmark state

The benchmark methods can receive a single argument, aside from `this`. The
types of these objects should obey the following rules:

* Have a no-argument constructor;
* Be a public class;
* Be static if it's an inner class;
* Be annotate with the `@State` annotation.

The `@State` annotation defines the scope of the instance:

* `Scope.Thread`
    * The default state; an instance will be allocated for each thread running
      the given benchmark.
* `Scope.Benchmark`
    * An instance will be shared across all threads running the same benchmark.
* `Scope.Group`
    * An instance will be allocated per thread group.

We can annotate state class methods with `@Setup` and `@TearDown` annotations.
There can be any number of setup/teardown methods. These methods do not contribute
to test times. A `Level` argument specifies when to call the housekeeping method:

* `Level.Trial`
    * The default level; before/after entire benchmark run (group of iterations).
* `Level.Iteration`
    * Before/after an iteration (group of invocations).
* `Level.Invocation`
    * Before/after every method call (beware of this mode, as it incurs some
      overhead that can actually influence test times).

Note that this also applies to the benchmark class, which is an implicit
parameter of the methods.

### Forks

JMH forks a new JVM for each run so it benefits from different compile plans,
the JVM having inherent non-determinism. By default JHM forks a new java process
for each trial (set of iterations) to account for the run-to-run variance. We
can configure the amount of forks with the `@Fork` annotation.

### Configuration

Aside from the `@Fork` annotation, the following ones allow further configuration:

* `@Measurement`
  * Configures the actual test phase parameters. We can specify the number of
    iterations, how long to run each iteration and the number of test invocations
    in the iteration (usually used with `@BenchmarkMode(Mode.SingleShotTime)` to
    measure the cost of a group of operations, instead of using loops).
* `@Warmup`
  * Same as `@Measurement`, but for warm-up phase.
* `@Threads`
  * Configures the number of threads to use for the benchmark; the default is
    1, but we should use `Threads.MAX`, which is set to
    `Runtime.getRuntime().availableProcessors()`.

They can be applied on each of the benchmark methods or at the class level, to
have effect over all the benchmarks. The annotation in the closest scope takes
precedence.

### Parameters

In many situations, a trial requires walking the benchmark configuration space.
This is needed to investigate how the workload performance changes with different
settings. JMH defines the `@Param` annotation on fields of `@State` objects,
which expects an array of Strings. These Strings will be converted to the field
type before any `@Setup` method invocations. JMH will use an outer product of
all `@Param` fields: if a first field has 2 parameters and a second has 5, the
test will be executed 2 \* 5 \* _forks_ times.

### Compiler hints

We can use HotSpot-specific functionality to instruct the compiler what to do
with _any_ method, not only those annotated by `@Benchmark`:

* `CompilerControl.Mode.DONT_INLINE`
  * Annotated methods are prohibited from being inlined. This is useful to
    measure the method call cost and to evaluate if it worth to increase the
    inline threshold for the JVM.
* `CompilerControl.Mode.INLINE`
  * Forces the method to be inlined. Usually used in conjunction with
    `Mode.DONT_INLINE` to evaluate pros and cons of inlining.
* `CompilerControl.Mode.EXCLUDE`
  * Prohibits the method from being JIT compiled.

### Dead-code

To avoid dead-code elimination we mustn't write void-returning tests. Instead
we should return the result of the calculation, and JMH will ensure the JIT
can't eliminate it. To return multiple values, we can either combine them with
some cheap operation (compared to the cost of the operations by which we got the
results) or use a `Blackhole` argument and sink all the results into it by
calling `Blackhole::consume()`. `Blackhole` is a `@State` object bundled with
JMH. It is thread-scoped and can also be passed to both `@Setup` and `@TearDown`
methods. Note that to limit thread-local optimizations, `Blackhole` needs to
escape an object by assigning it to a field of some object in the heap, which
entails a GC write barrier; this is an inescapable overhead.

### Burning CPU

The `BlackHole` can also consume time with the `BlackHole.consumeCPU(int tokens)`
method, where a token is some CPU cycles. Burning CPU is usually preferable to
parking a thread because that can activate the power management subsystem, which
balances power vs performance by, for example, boosting the speed of other cores
(i.e., Intel's TurboBoost). Some power-aware OS schedulers can also interfere
when parking threads.

### Constant folding

If the JVM realizes the result of a computation is the same no matter what, it
can cleverly optimize it away. To avoid that, we must always read the test input
from a state object, aside form returning or consuming results.

### Loops

Benchmark methods typically contain a loop where in each iteration the target
code is invoked. The JVM, however, heavily optimizes loops with potential
impact on the benchmark's results. While loop unrolling is beneficial and
can allow the benchmark to run faster, invariant hoisting can be harmful:

```java
int x = 1, y = 2;

@Benchmark
public long measureWrong(Blackhole blackhole) {
    for (int i = 0; i < 10000; ++i) {
        blackhole.consume(x + y);
    }
}
```

Can be transformed into

```java
int x = 1, y = 2;

@Benchmark
public long measureWrong(Blackhole blackhole) {
    int r = x + y;
    for (int i = 0; i < 10000 / 4; i += 4) {
        blackhole.consume(r);
        blackhole.consume(r);
        blackhole.consume(r);
        blackhole.consume(r);
    }
}
```

### Multi-threading

Running a benchmark with multiple threads requires coordination so that all
threads start executing the benchmark at the same time. The traditional way
to do this is to park all thread on a barrier and release them at once. However,
that does not guarantee the threads will start at the same time, meaning that
the period of inactivity is added to the benchmark's total run time.

The solution is to introduce bogus iterations, ramp up the threads executing
the iterations, and then atomically shift the system to actual measuring. The
same thing can be done during the ramp down.

JMH does this automatically for us.

### Asymmetric testing

We can annotate benchmarks with the `@Group(name)` annotation, binding them
together. Each benchmark in a group can be annotated with the `@GroupThreads(threadsNumber)`
annotation, which defines how many threads are participating in running that
specific method. This enables us to define non-uniform access to the state
objects, for example to test reader/writer code.

JMH starts the sum of the `@GroupThreads` for a given group and runs all tests
in that group concurrently in the same trial. The results will be given for the
group and for each method independently.

### False sharing

To help avoid false sharing, `@State`-annotated fields are automatically padded
by JMH. However, this only covers `@State` fields in the benchmark class. We
must pad the internal fields of `@States` manually, either using techniques
similar to the ones we use in our `PaddedAtomicXxx` classes or using Java 8's
`@sun.misc.Contended` attribute.

### Interruptions

JMH can detect when threads are stuck in the benchmarks and tries to forcefully
interrupt them. JMH tries to do that when it is arguably sure it would not
affect the measurement.

### Control

JMH exposes another (experimental) built-in state object named `Control`, which
allows us to tap into the framework's control variables. For example, it exposes
a flag that is set when the trial should end, so that we can poll it in case
we're on some potentially long-running loop.

In the following code, we want to estimate the ping-pong effect of cache lines.
Because one of the threads could be stuck in the `while` loop after the other
one exits, we need a way to terminate the loop:

```java
@State(Scope.Group)
public class PingPong {
    public final AtomicBoolean flag = new AtomicBoolean();

    @Benchmark
    @Group("pingpong")
    public void ping(Control cnt) {
        while (!cnt.stopMeasurement && !flag.compareAndSet(false, true)) {
        }
    }

    @Benchmark
    @Group("pingpong")
    public void pong(Control cnt) {
        while (!cnt.stopMeasurement && !flag.compareAndSet(true, false)) {
        }
    }
}
```

### Programmatically consuming results

We can give our benchmark class a `main()` method that allows us to run the
benchmarks and programmatically consume the results:

```java
public class Benchmark {
    @Benchmark
    public int measureSomething() {
        return ...
    }


    public static void main(String[] args) throws RunnerException {
        Options baseOpts = new OptionsBuilder()
                .include(".*" + Benchmark.class.getName() + ".*")
                .warmupTime(TimeValue.milliseconds(200))
                .measurementTime(TimeValue.milliseconds(200))
                .warmupIterations(5)
                .measurementIterations(5)
                .forks(1)
                .verbosity(VerboseMode.SILENT)
                .build();

        RunResult runner = new Runner(baseOpts).runSingle();
        Result baseResult = runner.getPrimaryResult();

        System.out.printf("Score: %10.2f %s%n",
            baseResult.getScore(),
            baseResult.getScoreUnit()
        );
}
```

This can allow us to programmatically find local maximum by exploring the
configuration space. A more complex example is [here](http://hg.openjdk.java.net/code-tools/jmh/file/d985a0e68548/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_25_API_GA.java)

### Things not covered

Some things are not covered here as they're considered corner or more advanced
cases.

* [Auxiliar counters](http://hg.openjdk.java.net/code-tools/jmh/file/d985a0e68548/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_23_AuxCounters.java)
* [Inheritance](http://hg.openjdk.java.net/code-tools/jmh/file/d985a0e68548/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_24_Inheritance.java)
* [Batch size](http://hg.openjdk.java.net/code-tools/jmh/file/d985a0e68548/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_26_BatchSize.java)
* [Internal parameters](http://hg.openjdk.java.net/code-tools/jmh/file/d985a0e68548/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_31_InfraParams.java)

## How to run

We run benchmarks using Gradle. The following command executes benchmarks that
obey the specified pattern in the midonet-util project:

`./gradlew :midonet-util:benchmarks '-Pjmh=.*Statistical.*'`

We can omit the arguments and all benchmarks in the midonet-util project will
run. We can also specify more arguments, like `-wi 5 -i 5 -t 2 -f 5`, which
configure the number of warm-up iterations, of iterations, of threads and of
forks, respectively.

If we omit the project name, all benchmarks across all projects will be run.

Alternatively, we can invoke the `shadowJar` task, which creates an executable
JAR:

`./gradlew :midonet-util:shadowJar`

We can then run this JAR with `java -jar midonet-util/build/libs/benchmarks.jar`,
which will run all the benchmarks of midonet-util. We can also specify the
aforementioned parameters to select and configure the benchmarks we wish to run.

If we omit the project name, an executable `benchmark.jar` JAR will be created
for all projects.

## Profiling

The JMH framework includes various profilers. Running

`./gradlew :midonet-util:benchmarks '-Pjmh= -lprof'`

yields the following list of supported profilers:

* cl: Classloader profiling via standard MBeans
  * Gives us the number of classes loaded and unloaded per iteration. To actually
    get these numbers we should run it with `@Warmup(iterations = 0)`.

* comp: JIT compiler profiling via standard MBeans
  * Gives us the time spent in JIT compilation on the current iteration. This is
    useful to fine-tune the number of warm-up iterations.

* gc: GC profiling via standard MBeans
  * Gives us the time spent in garbage collection during a test and the number of
    garbage collections (major and minor collections cannot be distinguished by
    this profiler).

* hs_cl: HotSpot (tm) classloader profiling via implementation-specific MBeans
  * All the HotSpot profilers rely on JMX counters and on calculating the
    difference before and after an iteration. Gives us the difference between
    the classloading JMX counters.

* hs_comp: HotSpot (tm) JIT compiler profiling via implementation-specific MBeans
  * Gives us stats about the number of compiled blocks of code, OSRs, invalidated
    JIT'ed code and TLA bailouts. It will also output the amount of generated code
    and time spent on compilation (as well as number of compiler threads).

* hs_gc: HotSpot (tm) memory manager (GC) profiling via implementation-specific MBeans
  * Gives us the difference between the GC JMX counters.

* hs_rt: HotSpot (tm) runtime profiling via implementation-specific MBeans
  * Gives us information about intrinsic locks (inflated monitors, locks contented
    amount of parks, amount of notifies, of futile wakeups, etc.) and safepoints
    (time spent waiting on safepoints, for example for GC or for revoking a
    biased lock).

* hs_thr: HotSpot (tm) threading subsystem via implementation-specific MBeans
  * Gives us the amount of threads started and threads alive during each
    iteration.

* stack: Simple and naive Java stack profiler
  * Gives us a rough evaluation of methods which took most of your CPU. It works
    in a separate thread that wakes up periodically and gets stack traces of all
    application threads. It keeps top N stack lines until the end of test. This
    profiler is not much different from the sampling functionality of commercial
    profilers or jstack. It can be configured with the following parameters:
      * jmh.stack.lines
        * Number of top stack lines to save (default is 1). JMH will sort top
         sections to find out the biggest CPU consumers.
      * jmh.stack.top
        * Number of top stack traces to show in the profiler output; defaults to
          10.
      * jmh.stack.period
        * How often a profiler should take samples of your stack traces (in
          milliseconds). This heavily impacts other threads if it's too frequent.
          Defaults to 10 milliseconds.
      * jmh.stack.detailLine
        * Saves stack line numbers which allows us to distinguish between
          different lines in the same method. his feature works only with
          jmh.stack.lines=1.

To run a benchmark with a particular profiler attached, we can do

`./gradlew :midonet-util:benchmarks '-Pjmh= -prof stack .*Statistical.*'`

Note that we can run more than one profiler at the same time.

## Resources

* [Statistically Rigorous Java Performance Evaluation](http://buytaert.net/files/oopsla07-georges.pdf)
* [What the heck is OSR and why is it Bad (or Good)?](http://www.azulsystems.com/blog/cliff/2011-11-22-what-the-heck-is-osr-and-why-is-it-bad-or-good)
* [Nanotrusting the Nanotime](http://shipilev.net/blog/2014/nanotrusting-nanotime/)
* [JMH Samples](http://hg.openjdk.java.net/code-tools/jmh/file/d985a0e68548/jmh-samples/src/main/java/org/openjdk/jmh/samples)
