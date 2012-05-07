## midokura-util package contents.

-   *com.midokura.util.process.ProcessHelper*

    It is a class that allow easy and simple process execution and control.

    It can launch a process (with or without sudo), watch it, read and/or
    convert its output, kill it in more ways than one (if it's stuck on an
    infinite loop for example it will send a -SIGKILL signal)

    Also has the ability to launch processes either locally on the machine that
    the caller is launched or transparently on a remote machine using an
    underlying ssh connection to that machine.

    It can also force a process to be launched locally regardless if an remote
    host specification was provided at runtime.

-   *com.midokura.util.ssh.SshHelper*

    It's a basic class that provides a simple entry point for operations executed
    over a ssh connection to a remote host: file upload/download,
    local/remote port forwarding, remote process launch and control,
    remote command execution.

    It is used mainly by the ProcessHelper and RemoteHost
    to transparently execute remote commands and respectively forward ports at
    runtime if so desired.

- *com.midokura.util.SystemHelper*

    Easy way to find out the currently running OS type.

- *com.midokura.remote.RemoteHost*

    Class that will look for a managed_host.properties in the current classpath.
    If found it will read it and it it will try to open a ssh connection to the
    remote host specified in there while at the same time enabling a set of port
    forwardings (those port forwardings that have been already specified in there).

- *com.midokura.tools.timed.Timed*

    Provides support for conditional wait loops. Waiting up to x amount of
    milliseconds while checking for a condition every y amount of milliseconds.
    If the condition is true then you finish the wait quicker.

    Mainly used inside the functional tests.

- *com.midokura.util.functors*

    A couple of simple interfaces that abstract functions. Until we will have
    closures in java :).

- *some hamcrest matchers*

    Some simple Hamcrest matchers (used in the functional tests and in the controller).

