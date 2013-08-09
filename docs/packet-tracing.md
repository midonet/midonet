# Precis 

Prospective customers find the lack of easy visibility of how MidoNet's
virtual network handles traffic to be very unpleasant when operators
need to diagnose undesired MidoNet behavior.  We'll address this by
providing a way for a network operator to use the Control Panel or
MidoNet REST API to request traces of certain packets' traversal through
the MidoNet virtual network.  These traces would record each network
device's reception, processing, and disposition of each traced packet;
and will be viewable in the Control Panel and fetchable through the API.

# Design

## Infrastructure
The virtual network simulation represents the packet being simulated
with a `PacketContext` object, which includes data such as the raw bytes
of the packet, the port the packet is "at" in the current stage of
the simulation, a `WildcardMatch` object which matches the packet, etc.
To the `PacketContext` class we'll add a boolean field indicating whether
the packet is being traced, and a counter of what "step" number the
simulation is currently at.

## Requesting a trace

We introduce a `trace-conditions` directory in ZooKeeper, which
maps from `UUID` to `Condition`.  The `VirtualTopologyActor` keeps
a copy of the contents of this directory up-to-date locally in each
Midolman instance.  When a Midolman begins a simulation, it checks
if any of the Conditions match the packet -- if so, the trace flag
is enabled in the `PacketContext` object.

## Performing a trace 

Over the course of processing the packet, the `PacketContext` will be passed
to various elements of the virtual network until it is either consumed
by an element, dropped, rejected, or send out a port which leaves the
virtual network.  Each element will be modified to send messages recording
its dispatching of the packet to a table in Cassandra using fire-and-forget
write calls.  These messages will be keyed by a simulation ID and step
number.  (The host ID may be another key, or part of the simulation ID.)

## Viewing a trace 

The API server will have calls to fetch a list of the simulations stored
in the Cassandra table, fetch all the messages for a specified simulation,
and to remove a specified simulation's messages.  (I.e., list/read/delete
operations.)  The Control Panel will display a list of traces available
by using the API's list operation, and display or delete a selected
trace using the API's read or delete operation.

# REST API

`GET /traces`: Returns a list of trace IDs.

`GET /traces/$id`: Returns the contents of the trace.

`DELETE /traces/$id`: Removes the trace.

`GET /trace_conditions`: Returns a list of trace condition IDs.

`POST /trace_conditions`: Stores a trace condition with a server-assigned ID.

`GET /trace_conditions/$id`: Returns a trace condition.

`PUT /trace_conditions/$id`: Stores a trace condition with a client-assigned ID.

`DELETE /trace_conditions/$id`: Removes a trace condition.

# CLI API

`list trace-condition`: List the trace conditions with labels.

`show trace-condition $label`: Display a specific trace condition.

`add trace-condition $condition`: Create a trace condition.

`del trace-condition $label`: Remove a trace condition.

`list trace`: List the available traces with labels.

`show trace $label`: Display a specific trace.

`del trace $label`: Remove a trace.
