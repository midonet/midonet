## Profiling Midolman with Yourkit

###Motivation

YourKit provides two options for profiling a Java application.

* Starting the Java application with the profiling agent, by manually specifying
the option that attaches the YourKit profile to the `java` command.

* Attach the profiler on demand to a running JVM.

While the second approach is simpler and provides greater flexibility, it also 
presents some limitations due to limited set of capabilities provided by the JVM
to the attached profiling agents. These limitations are the following.

* Attach mode is supported only for Sun Java and JRockKit.

* Attach may fail due to insufficient access rights.

* The Sun Java client JVM may crash due to the JVM bug
[6776659](http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6776659).

* Starting the CPU tracing for the first time requires a long pause period,
while the profiler instruments the loaded classes.

* There is no profiling for some native method. These calls will be missed in
the CPU tracing results.

* Exception telemetry, which shows the exceptions thrown by the profiled
application, is not available.

###Starting Midolman with a Profiling Agent

In order to prevent the shortcomings of on-demand profiling, and also to
remove the overhead of attaching the profiling agent manually, it may be
preferable to start `midolman` with the profiling agent.

To this end, we add the following option to the `java` command starting
`midolman` on a 64-bit Linux system. Select the appropriate directory for
a different platform.

    -agentpath:<path-to-yourkit-directory>/bin/linux-x86-64/libyjpagent.so
    
When building MidoNet for performance testing, you can build Midolman with the
profiling option already included to the Midolman service. To this end, you
must modify the Midolman service configuration file found at
`/midolman/src/deb/init/midolman.conf`. In this file you can modify the JVM
options, such that it includes the YourKit profiling agent.

    JAVA_OPTS="$JVM_OPTS
        -Dmidolman.log.dir=$MIDO_LOG_DIR
        -Dconfig.file=$MIDO_AKKA_CFG
        -Dlogback.configurationFile=$MIDO_CFG/$MIDO_LOG_BACK
        -agentpath:<path-to-yourkit-directory>/bin/linux-x86-64/libyjpagent.so"

