<!-- vim: filetype=markdown
  -->

# Midolman fast path overview

<pre>
                              ┌─────────┐
                              │ Device  │
                           ┌─→│ Managers│
                           │  └─────────┘
                           │       ↑
                           │       │ flow removal
                           │       │ callbacks
                   Device  │       │
              book-keeping │  ┌────┴────────────┐
           (MAC->port, etc)│  │ Flow Controller │─────────┐
                           │  └─────────────────┘         │
                           │        ↑                     │
                           │        │                     │flow query /
                           │        │ Wildcard            │flow removal
                           │        │ Flows               │
                           │        │                     │
                           │        │                     ↓
┌──────────┐              ┌┴────────┴─┐              ┌───────────┐
│ Netlink  │┐  packets    │ Packet    │┐  packets    │ netlink   │┐
│ upcall   ││────────────→│ processors││────────────→│ request   ││
│ channels ││             │           ││  flows      │ channels  ││
└──────────┘│             └───────────┘│             └───────────┘│
 └──────────┘              └───────────┘              └───────────┘

</pre>

## Introduction and terminology

This document describes the overall design of the midolman fast path. We define
the fastpath as every component that executes code (potentially) for every
packet that misses the datapath flow table and makes its way up to userspace.

The most critical pieces of the fastpath are those directly involved in
processing packets, they affect both latency and throughput. Here we will say
that these components are part of the *direct fast path*.


However, those indirectly involved such as the FlowController are almost equally
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

`UpcalDatapathConnectionManager` is also responsible for maintaning the
Hierarchical Token Bucket structure and hooking the datapath channels to it.
Reads off the netlink channels are throttled by these buckets, with the goal
of achieving fair processing capacity distribution for all ports in the system
and isolating ports against DoS due to high load created by other ports.

Finally, the notification handler installed for each of these channels by
the `UpcallDatapathConnectionManager` is a batching one. It will accumulate
packets until the underlying channels and/or selectloops signal that a batch
of reads has concluded. At this point the batches will be delivered to the
packet processors in the next stage.

### Packet processing stage

This stage consists of the following sub-stages:

* Hash based routing to a `DeduplicationActor` instance
* Deduplication stage
* Pauseless non-blocking packet workflow
* Flow creation & packet execution

The packet processing stage runs as a group of parallel `DeduplicationActor`'s,
each pinned to their own thread. There is no synchronization between them. The
number of threads/actors dedicated to packet processing can be set via
configuration file. Once a `DeduplicationActor` picks up a packet to process
from its mailbox, the processing of the packet will happen synchronously and
non-blockingly until the packet is handed-off to the output stage. There are two
exceptions to this, which we will discuss in detail below.

#### Routing to a DeduplicationActor instance

It is a midolman invariant that two packets with the same flow match must not be
in simulation stage at the same time. This is the reason why the
`DeduplicationActor` is called like that.

In order to maintain this invariant, the notification callback installed in the
netlink input channels will route packets to particular `DeduplicationActor`
instances based on the flow match hash. Thanks to it, two packets with the same
flow match will always be delivered to the same `DeduplicationActor`, and this
actor will not need any coordination with any other actors of its kind.

`DeduplicationActor` instances are set up at startup by the `PacketsEntryPoint`
singleton actor. This actor can be queried to return its list of worker
children.

Even though packets need to be distributed across a group of different
`DeduplicationActor` instances, the notification callback sends packets to them
in batches.

The motivation for this batching is the cost of waking a up an idle thread when
messaging an actor running in a thread or thread-pool that contains idle
threads. The cost is high enough that, should there be no batching, a single
netlink thread could spend more time waking up `DeduplicationActor`  threads
than these threads would spend in processing each packet. Thus becoming a
bottleneck to the system.

Packet batches are delivered when a batch "ends". A batch ends when the netlink
channel sees there's nothing waiting to be read (the batch is ended before
initiating a new select(2) call). A batch may also end when the notification
handler has accumulated enough packets.

#### Deduplication stage

In the deduplication stage the packet's flow match is checked against the
currently in-progress packets. If a packet with the same flow match is found
to be in flight, then the new packet is queued.

This may sound like a contradiction: we stated above that packet processing
is synchronous and non-blocking. If that's the case, then by definition
deduplication shouldn't be necessary, because there's only ever one packet in
flight. The premise however is not 100% true, packet processing needs to
perform asynchronous requests on occasion: when midolman doesn't have a locally
cached copy of a needed virtual device, when a virtual router decides to send
and ARP request and has to wait for a reply, etc. How midolman handles these
cases will be the subject of the next section.

#### Pauseless non-blocking packet workflow

Once a packet gets past deduplication, it's ready to be processed. The class
that drives the work to be done on a packet is `PacketWorkflow`.

The workflow spans these stages:

1. Querying the wildcard flow table.
2. Translation of input port into a virtual topology port.
3. Packet simulation.
4. Flow translation: maps virtual information in simulation results to physical information.
5. Flow creation / packet execution.

All of these stages, just as deduplication before them, need to be synchronous
and non-blocking. Maintaining these invariants gives us several advantages:

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
* Reserving SNAT blocks (future work).

Because these potentially asynchronous operations are not needed most of the
time. Midolman wraps Scala's `Future` monad in its own `Urgent` monad. `Urgent`
represents potentially asynchronous results that are synchronous most of the
time. When the result is readily available `Urgent` executes code linearly
without touching Akka or the thread pool. When it's not, `Urgent` contains
the `Future` that stopped the computation from completing synchronously.

These two `Urgent` outcomes are represented by its two subclasses: `Ready` and
`NotYet`.

When a `PacketWorkflow` returns a `NotYet`, the `DeduplicationActor` will cancel
the workflow and put it in the `WaitingRoom`.

The `WaitingRoom` has a limited capacity (given by the fact that very few
packets should enter it). Packets will exit the waiting room when it's full,
when they timeout, or when their wrapped `Future` completes. In the latter case,
the `DeduplicationActor` will restart their `PacketWorkflow` from scratch.

It's important to note that the `DeduplicationActor` will throw away the result
of the completed future, it will just use its successful completion as a signal
that it can try to process the packet again. The implication is extremely
important: when the packet is processed a second time, the piece of data
(a virtual device, an ARP table entry, etc) which was missing the first time
around needs to be locally cached the second time. Otherwise the packet would
never be processed successfully.

Let's illustrate this point by outlining what happens when a device is found to
be missing during packet processing:

1. `DeduplicationActor` starts processing a packet, it starts running its
simulation across the virtual topology.
2. The packet enters a bridge. The `VirtualTopologyActor` hadn't heard of this
bridge before. It returns a `NotYet` wrapping the future for the bridge.
3. The packet is put in the waiting room.
4. A while later, the bridge arrives. The `VirtualTopologyActor` saves the
bridge in its local cache and completes the future that it returned above.
5. As the future completes, the `DeduplicationActor` takes the packet from the
waiting room and starts its simulation again.
6. The packet arrives to the same bridge again, this time the
`VirtualTopologyActor` can return a `Ready` that contains the bridge, and the
simulation can continue further along.

#### Flow creation and packet execution

From the `PacketWorkflow`'s point of view, this is simply consists on executing
calls on the `OvsDatapathConnection`. This transfers the requests to the output
stage, covered below.

#### Back pressure from the FlowController

When the `PacketWorkflow` creates a new flow, it needs to notify the
`FlowController`, who is responsible for all the associated bookkeeping.

The `FlowController`, being an actor and thus single-threaded, may process
requests slower than the packet processing phase can send them. This condition
is fatal, it will fill up the `FlowController`'s mailbox and make midolman
exhaust its memory.

To prevent this, there's a mechanism to apply back-pressure from the
`FlowController` back onto the packet-processing stage (which will in turn
apply back pressure to the input stage thanks to the HTB).

This mechanism solves a second need: a `DeduplicationActor` cannot unpend the
duplicates for a flow match until the `FlowController` has added the new
`WildcardFlow` to the wildcard flow table. Otherwise there would be a loophole
in deduplication because new duplicates could come up from the kernel after
the duplicates are unpended, and make its way into simulation if it reached the
wildcard flow table before the WildcardFlow.

To solve both problems, each `DeduplicationActor` contains a small
`ActionsCache`. The cache is simply a ringbuffer with flow matches and their
actions. The cache acts as a second layer of deduplication. If a packet's match
is found in the cache, the cached actions will be applied immediately without
further processing.

The happens-before relationship between the addition of the `WildcardFlow` and
the unpending of packets is achieved by this sequence of events:

1. At the end of the packet processing phase, the final actions are added to the
`ActionsCache`. From this point on, and until they are cleared from the cache,
they will be applied to all duplicate packets that come up from the kernel.
2. The `WildcardFlow` is sent to the `FlowController`, along with the position in
the `ActionsCache` that contains the actions for this new flow.
3. The `FlowController` adds the flow to the wildcard flow table.
4. The `FlowController` removes the entry from the `ActionsCache`
5. New duplicates will be hit on the wildcard flow table.

We get back pressure through the `ActionsCache` implementation:

* Its capacity is limited (it's essentially a ring buffer).
* When the `FlowController` falls behind, the cache will fill up.
* The `DeduplicationActor` will spin if the cache is full, waiting for the
`FlowController` to catch up.

### Output stage

The output stage is a pool of netlink channels (their number is configurable)
set up and managed by subclasses of `DatapathConnectionPool`. Currently there's
only one subclass: `OneToOneConnectionPool`, with these characteristics:

* Two threads per channel: one for reading, one for writing.
* Select loop based.
* Non-blocking I/O.

These channels read requests from the rest of the system from a queue. So their
behaviour and performance related caveats are similar to those an akka actor.

Finally, to keep correctness in flow management, `DatapathConnectionPool` gives
users datapath connections taking a hash argument. This is to ensure that all
operations pertaining to the same datapath flow happen on the same channel and
thus maintain the same ordering in which they were requested.

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
own queue/mailbox.

The queue is non-blocking and lock-free. Writers will incur in an expensive call
to unpark() if the reader thread is idle.

### Packet processing to output stage hand-off

*Number of writers*: N, one per packet processing thread.

*Number of readers*: always one. Each output channel uses a dedicated write
thread.

As with the other stage, the queues are non-blocking and lock-free but writers
may need to wake up the output thread when they write to an empty queue.

## Indirect fast path components

There are a small number of components that don't directly sit in the path of
packets but whose load is propotional to the amount of packets processed,
becoming potential system-wide bottlenecks or, worse, stability hazards if they
fail to keep up. The only such example where back-pressure measures have been
applied is the `FlowController`, explained above.

But some other examples exist too:

* Flow invalidation in the routing table. (`RouterManager`)
* Flow book keeping (`FlowController`, `FlowManager`).
* MAC learning table (`BridgeManager`).
* ARP table (`ArpTableImpl`).
