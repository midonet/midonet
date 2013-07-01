# Precis

Prospective customers find the lack of easy visibility of how MidoNet's
virtual network handles traffic to be very unpleasant when operators
need to diagnose undesired MidoNet behavior.  We'll address this by
providing a way for a network operator to use the MidoNet REST API or
MidoNet CLI client to request traces of certain packets' traversal through
the MidoNet virtual network.  These traces would record each network
device's reception, processing, and disposition of each traced packet;
and will be viewable in the CLI and fetchable through the API.
(Future direction:  Also have this available through the web Control Panel.)

# Proposal

## Infrastructure
The virtual network simulation represents the packet being simulated
with a `PacketContext` object, which includes data such as the raw bytes
of the packet, the port the packet is "at" in the current stage of
the simulation, a `WildcardMatch` object which matches the packet, etc.
To the `PacketContext` class we add a boolean field indicating whether
the packet is being traced, and a counter of what "step" number the
simulation is currently at.

## Requesting a trace

We have a `ReplicatedSet` of `Condition`s to trigger traces
(the `Condition`s being the same as those used for matching Chain Rules).
When the `Coordinator` starts a simulation, it asks the set whether
the packet to simulate matches any of its `Condition`s.  If so, it
sets the flag on the `PacketContext` object to indicate tracing.
The set will be modified by the API Server only, which will add and
remove entries based on create/delete calls made to the API.
The ZooKeeper path to the set's underlying directory is "traced-conditions"
under the Midolman Root Directory.
In the CLI, operators will be able to add and delete `Condition`s which
trigger the tracing.

## Performing a trace

Over the course of processing the packet, the `PacketContext` will be passed
to various elements of the virtual network until it is either consumed
by an element, dropped, rejected, or send out a port which leaves the
virtual network.  If the `PacketContext` has its trace flag set, the
`Coordinator` records every network element it sends the packet to and
the packet's final disposition to Cassandra using fire-and-forget write calls.
These messages are keyed by a trace ID and step number.  Viz, a Cassandra
key of "*traceID*:*traceStep*" under the "trace_messages" column family,
and a value consisting of the time in the format "yyyy-MM-dd HH:mm:ss.SSS",
a space, the UUID of the component emitting the message or else "(none)",
a space, and unstructured text descriping the traced operation.  A list
of all available packet traces is kept under the "trace_index" column
family, with keys consisting of the trace ID and values of the number
of messages in the trace.

## Viewing a trace

The API server will have calls to fetch a list of the traces stored
in the Cassandra table, fetch all the messages for a specified trace,
and to remove a specified trace's messages.  (I.e., list/read/delete
operations.)  The CLI will display a list of traces available
by using the API's list operation, and display or delete a selected
trace using the API's read or delete operation.

## Future directions

Features we'd like for the future include the ability to synthesize
a "logical packet" which would be traced, but not emitted out of
MidoNet; and the ability to suppress a datapath flow and its
associated WildcardFlow for one packet which would be traced.
