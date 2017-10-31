## Midolman fast reboot mechanism

Since MidoNet v5.6, there's a new mechanism to reboot a MidoNet Agent
(a.k.a midolman) that reduces the downtime to about one second, compared
to the 30+ seconds of previous versions.

The mechanism is based on starting a separate MidoNet Agent while the previous
one still processes traffic. This way, the initialization process of the
new MidoNet Agent is done in the background. The shutdown of the old agent and
the startup of the new agent is coordinated as to reduce to the minimum the
downtime time. The memory requirements during the reboot process are doubled,
as two MidoNet Agents co-exists for a short period of time. After the reboot
process is finished, memory usage goes back to normal.

To start the fast reboot mechanism, the operator just needs to issue
the command:

    $ mm-reboot

**NOTE** This command sends a SIGUSR1 signal to the *wdog* process, which is
the process responsible to start and monitor the new agent. If the *wdog*
mechanism is disabled, the fast reboot mechanism is not available.

**NOTE** If the operator prefers to run a standard reboot, the regular reboot
command is still available (e.g. using upstart or systemctl process managers).
