## midonet-util package contents.

* org.midonet.util.process.ProcessHelper*

    It is a class that allow easy and simple process execution and control.

    It can launch a process (with or without sudo), watch it, read and/or
    convert its output, kill it in more ways than one (if it's stuck on an
    infinite loop for example it will send a -SIGKILL signal)

    Also has the ability to launch processes either locally on the machine that
    the caller is launched or transparently on a remote machine using an
    underlying ssh connection to that machine.

    It can also force a process to be launched locally regardless if an remote
    host specification was provided at runtime.

* org.midonet.tools.timed.Timed*

    Provides support for conditional wait loops. Waiting up to x amount of
    milliseconds while checking for a condition every y amount of milliseconds.
    If the condition is true then you finish the wait quicker.

    Mainly used inside the functional tests.

* org.midonet.util.functors*

    A couple of simple interfaces that abstract functions. Until we will have
    closures in java :).

* some hamcrest matchers*

    Some simple Hamcrest matchers (used in the functional tests and in the controller).
    
* midonet.util.collection.*

    A few collection classes: object pools, a scala bidirectional map, a trivial ring buffer...
    
* org.midonet.util.eventloop

    A single threaded select loop.
    
* org.midonet.util.concurrent

    Concurrency utilities: execution contexts for Akka actors, a lock-free reference counted map with timed expiration, and padded atomics, a statistical counter, and more.

* org.midonet.util.TockenBucket et al.

    An HTB implementation used to distribute available processing capacity among local ports.

