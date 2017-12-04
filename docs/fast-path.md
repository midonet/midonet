<!-- vim: filetype=markdown
  -->

# Midolman fast path overview

<pre>
                              +---------+
                              | Device  |
                           +->| Mappers |
                           |  +---------+
                           |       ^
                           |       | flow removal
                           |       | callbacks
                   Device  |       |
              book-keeping |  +-----------------+
           (MAC->port, etc)|  | Flow Controller |+--------+
                           |  +-----------------+|        |
                           |   +----|------------+        |
                           |        |                     |
                           |        |                     |flow removal
                           |        |1:1 association      |
                           |        |                     |
                           |        |                     v
+----------+              +-----------+              +-----------+
| Netlink  |+  packets    | Packet    |+  packets    | netlink   |+
| upcall   ||------------>| processors||------------>| request   ||
| channels ||             |           ||  flows      | channels  ||
+----------+|             +-----------+|             +-----------+|
 +----------+              +-----------+              +-----------+

</pre>

## Introduction and terminology

This document describes the overall design of the midolman fast path. We define
the fastpath as every component that executes code (potentially) for every
packet that misses the datapath flow table and makes its way up to userspace.

The most critical pieces of the fastpath are those directly involved in
processing packets, they affect both latency and throughput. Here we will say
that these components are part of the *direct fast path*.


However, those indirectly involved are almost equally
important because they can become a bottleneck to the overall throughput of the
system, if they fail to keep up with the current throughput requests pile up
eventually crashing the daemon. If back pressure is applied to avoid such a
crash, then they become a direct bottleneck. We will refer to these components
as part of the *indirect fast path*.

## Direct fast path stages

The direct fast path is organized in three independent stages, each with
independently controlled threading:

* Input
* Packet processing
* Output

### Input stage

The input stage gets packet off the netlink channel and delivers them to the
packet processing stage.

This stage is set up and managed by the `UpcallDatapathConnectionManager`. In
all cases this component will create a new netlink channel (datapath connection)
for each datapath port in the system. It then asks the datapath to send
notifications (packets) for this port through this dedicated channel.

IO for these channels is non-blocking and uses a select loop.

There are two possible dedicated threading models for this stage:

* one-to-many: A single thread and a single select loop is used for all the
input channels.
* one-to-one: Each channel gets its own thread and select loop.

All of the above is wired by the `UpcallDatapathConnectionManager`, which is
responsible for the lifecycle of the channels, ports, threads and select loops.

`UpcallDatapathConnectionManager` is also responsible for maintaning the
Hierarchical Token Bucket structure and hooking the datapath channels to it.
Reads off the netlink channels are throttled by these buckets, with the goal
of achieving fair processing capacity distribution for all ports in the system
and isolating ports against DoS due to high load created by other ports.

Finally, the notification handler installed for each of these channels by
the `UpcallDatapathConnectionManager` will dispatch packets to
packet processors chosen with the packets' flow hash values,
via Disruptor RingBuffers.

### Packet processing stage

This stage consists of the following sub-stages:

* Pauseless non-blocking packet workflow
* Flow creation & packet execution

The packet processing stage runs as a group of parallel
packet processors, which is implemented with Disruptor BatchEventProcessor,
each pinned to their own thread.  There is no synchronization between them. The
number of threads dedicated to packet processing can be set via
configuration file. Once a packet processor picks up a packet to process
from its Disruptor RingBuffer, the processing of the packet will happen
synchronously and non-blockingly until the packet is handed-off to the
output stage. There are exceptions to this, which we will discuss in
detail below.

#### Packet workers stage

We stated above that packet processing is synchronous and non-blocking.
If that's the case, then there's only ever one packet in flight.
It however is not 100% the case. Packet processing needs to
perform asynchronous requests on occasion: when midolman doesn't have a locally
cached copy of a needed virtual device, when a virtual router decides to send
and ARP request and has to wait for a reply, etc. How midolman handles these
cases will be the subject of the next section.

#### Pauseless non-blocking packet workflow

The class that drives the work to be done on a packet is `PacketWorkflow`.

The workflow spans these stages:

1. Translation of input port into a virtual topology port.
2. Packet simulation.
3. Flow translation: maps virtual information in simulation results to physical information.
4. Flow creation / packet execution.

All of these stages need to be synchronous and non-blocking. Maintaining
these invariants gives us several advantages:

* We can keep tight control on the number of packets in flight in the system. If
asynchronous requests were allowed, it'd be very hard to predict how long a
packet would take to be processed. This opens the possibility for aggressive and
alternative designs for the fast path packet pipeline, such as one based on ring
buffers.
* The advantages of non-blocking are more obvious, if a processing thread is
allowed to do blocking operations, it may severely affect latency of those
packets that are behind in the queue. Blocking ops would void the advantages
of being totally synchronous. Thus, we want both: synchronous and non-blocking.

It follows from the above: the packet processing stage cannot do any type of IO,
including remote calls.

As we explained in the previous section, there are packets, a *vast minority*,
that need to perform asynchronous operations. These are the known cases:

* Loading pieces of virtual topology for the 1st time.
* Waiting for ARP replies.
* Reserving SNAT blocks.

Because these potentially asynchronous operations are not needed most of the
time.  When it's needed, the packet worker thread rewind its stack by
raising a special exception, NotYetException, which wraps Scala's `Future`
for the asynchronous operation.

When a `PacketWorkflow` catches a `NotYetException`, it will cancel the
workflow and put it in the `WaitingRoom`.

The `WaitingRoom` has a limited capacity (given by the fact that very few
packets should enter it). Packets will exit the waiting room when it's full,
when they timeout, or when their wrapped `Future` completes. In the latter case,
the `PacketWorkflow` will restart their workflow from scratch.

It's important to note that the `PacketWorkflow` will throw away the result
of the completed future, it will just use its successful completion as a signal
that it can try to process the packet again. The implication is extremely
important: when the packet is processed a second time, the piece of data
(a virtual device, an ARP table entry, etc) which was missing the first time
around needs to be locally cached the second time. Otherwise the packet would
never be processed successfully.

Let's illustrate this point by outlining what happens when a device is found to
be missing during packet processing:

1. `PacketWorkflow` starts processing a packet, it starts running its
simulation across the virtual topology.
2. The packet enters a bridge. The `VirtualTopology` hadn't heard of this
bridge before. It raises a `NotYetException` wrapping the future for the bridge.
3. The packet is put in the waiting room.
4. A while later, the bridge arrives. The `VirtualTopology` saves the
bridge in its local cache and completes the future that it returned above.
5. As the future completes, the `PacketWorkflow` takes the packet from the
waiting room and starts its simulation again.
6. The packet arrives to the same bridge again, this time the
`VirtualTopology` can return the cached bridge, and the simulation can
continue further along.

#### Flow creation and packet execution

From the `PacketWorkflow`'s point of view, this is simply consists on executing
calls on the `DatapathChannel`. This transfers the requests to the output
stage, covered below.

### Output stage

The output stage is a pool of netlink channels and threads
(their number is configurable)
managed by `DatapathChannel`.

These channels read requests from the rest of the system via
a Disruptor RingBuffer.

## Coordination between fast path stages

Packet hand-off between pipeline stages affects both throughput and scalability
of the system. Ideally, there should be as few stages as possible, to reduce the
number of hand-offs and each hand-off should:

* Have no coordination and contention between threads. Whether it's between
several writers, between the read and write sides or among several readers.
* Be non-blocking for writers.
* Allow back-pressure to be applied so that packets waiting for a certain stage
don't queue up ad-infinitum.

Both of the current hand-offs are based on queues. We explain their
characteristics below.

### Input stage to packet processing hand-off

*Number of writers*: one in the one-to-many input threading model. N in the
one-to-one model. This is because all input channels will spread out their
packets to all available packet processing threads.

*Number of readers*: always one. Because each packet processing thread gets its
own queue.

The queue is non-blocking and lock-free. Writers will incur in an expensive call
to unpark() if the reader thread is idle.

### Packet processing to output stage hand-off

*Number of writers*: N, one per packet processing thread.

*Number of readers*: M, one per output thread.

As with the other stage, the queues are non-blocking and lock-free but writers
may need to wake up the output threads when they write to an empty queue.

## Indirect fast path components

There are a small number of components that don't directly sit in the path of
packets but whose load is propotional to the amount of packets processed,
becoming potential system-wide bottlenecks or, worse, stability hazards if they
fail to keep up.

Some examples:

* VirtualTopology
* Flow invalidation in the routing table. (`RouterMapper`)
* MAC learning table (`BridgeMapper`).
* ARP table (`ArpRequestBroker`).
