## Overview

Rule logging is the general mechanism underlying firewall logging and the
proposed
[security group logging](https://blueprints.launchpad.net/neutron/+spec/security-group-logging)
feature. It allows us to log the results of evaluating a packet against a
Midonet rule for acceptance or rejection. Specifically, the following fields are
logged:

* Time
* Chain ID
* Rule ID
* Logger ID
* Source IP
* Destination IP
* L4 Source Port
* L4 Desination Port
* L4 Protocol (ICMP/TCP/UDP)
* Metadata (sequence of string key/value pairs)
* Result (ACCEPT or DROP)

For firewall logging, the metadata currently consists of the firewall ID
("firewall\_id") and tenant ID ("tenant\_id").

A logger can be configured to log only accepted packets, only dropped packets,
or both. Note that the "result" field reflects only whether the packet matched
the specific rule with which the logger is associated; if a packet is accepted
by the firewall but the simulation ultimately rejects the packet due to security
group rules or an unroutable IP address, the value of the "result" field in the
firewall logs will be "ACCEPT".

To avoid performing string formatting and the resulting memory allocations in
the fast path, records are logged in a binary format. The SBE schema for the
logs is in rulelogs.schema.xml. A separate tool for converting the binary logs
to text, mm-logxl, is provided as part of the Midolman package.

The location and file rotation behavior of the logs can be set with mn-conf,
using the settings under agent.rule\_logging. Rotation frequency may be specified
either as a period of time or as a size. If the rotation frequency is specified
as a size, we guarantee that log files will be no larger than the specified
size. However, time-based rotation is only approximate; a packet simulated a
fraction of a second before the rollover time may end up in the next period's
log file.

By default (defined in the mn-conf schema in agent.conf), files are logged to
rule-binlog.rlg in the Midolman log directory, usually /var/log/midolman, and
rotated once per day, with old files being compressed with gzip. Default
retention is 14 files in addition to the current file, which with daily rotation
means two weeks. Old log files follow the standard logrotate naming convention,
adding .1.gz, .2.gz, etc. to the base file name. So the current log will be
rule-binlog.rlg, yesterday's will be rule-binlog.rlg.1.gz, then
rule-binlog.rlg.2.gz for the day before yesterday, and so on up to
rule-binlog.rlg.14.gz for the log from fourteen days ago.

## Topology

There are two new Midonet topology objects, LoggingResource and RuleLogger.
LoggingResource indicates the type of the logging destination and an enabled
flag. Currently the only logging destination supported is local file, but in the
future we may support logging to a network destination.

Each LoggingResource can have zero or more RuleLoggers associated. A RuleLogger
specifies the type of event to be logged (ACCEPT, DROP, or ALL), and the ID of
the chain for which it should log events. Currently a RuleLogger may only be
associated with a chain, which will cause it to log events for all rules on that
chain, but in the future we may allow associating it with one specific rule.
When the parent LoggingResource is set to disabled, this disables all child
RuleLoggers.

## Simulation

The only new simulation object is RuleLogger. This is created by the
RuleLoggerMapper, and requested by the ChainMapper when building a Chain that
has one or more RuleLoggers associated with it. The implementation of the
simulation RuleLogger is trivial: It contains two public methods, logAccept()
and logDrop(), that are invoked by Chain.apply() when a rule matches a packet
with a result of ACCEPT or RETURN (logAccept), or DROP or REJECT (logDrop). Both
methods simply hand off information about the event to the RuleLogEventChannel.

## RuleLogEventChannel

The RuleLogEventChannel publishes logging events to a ring buffer, to be
asynchronously consumed and logged by the RuleLogEventHandler. Using a ring
buffer allows us to avoid allocation and garbage generation during the
simulation, and asynchronous logging avoids blocking the simulation for disk
I/O.

## RuleLogEventHandler

RuleLogEventHandler receives log events from the aforementioned ring buffer and
writes them to an OutputStream. The creation of the OutputStream is left to
subclasses, depending on the type of logging required.

The only subclass currently implemented is FileRuleLogEventHandler. This creates
a RollingOutputStream and writes a header containing some metadata about the SBE
format. To minimize disk head movement, we write events for all file-type
RuleLoggers to a single file, located by default in the Midolman log directory.

## RollingOutputStream

RollingOutputStream is an implementation of OutputStream that automatically
rotates files via execution of the external logrotate utility. As noted in the
overview, it supports both time-based and size-based rotation, with optional
compression via gzip. Note that RollingOutputStream is an abstract class with
various traits that can be mixed in to complete the implementation.

## FWaaS Logging

FWaaS logging implemented at the translation layer via a fairly straightforward
mapping of the Neutron FirewallLog and LoggingResource objects to the equivalent
Midonet RuleLogger and LoggingResource objects. See the Neutron Translation spec
for details. Note that firewall logging as such does not exist in Midolman,
which only knows about abstract rule logging.

## Decoding

As noted above, the mm-logxl tool decodes the binary logs. Usage is

> ```mm-logxl [-o OUTFILE] [-z] INFILE```

If -o OUTFILE is specified, the output will be
written to that file; otherwise it will be written to stdout. If -z is
specified, INFILE is assumed to be zipped with gz and will be unzipped to a
temporary file before decoding.
