## Introduction

Midolman is the controller for the *MidoNet* SDN system.  The core function
of Midolman is to receive notifications of new, unhandled network flows
(generally TCP connections) from the kernel's Open Datapath (ODP) module
and instruct the kernel how to handle them.  The datapath communication is
described in the [flow installation document](flow-installation.md).  To
figure out how to handle the flows, Midolman runs a simulation of the MidoNet
virtual topology, described in [the design overview](design-overview.md).
The state of the virtual network is kept in actors, and made available to
other actors running the simulations in RCU copies, as described in the
[actor model](actors-model.md).

## Subsystems

There are multiple logical components of Midolman not directly related to
the network simulation discussed below.

### Monitoring

This component handles the collection and export of the metrics gathered by the
system. It is explained at length inside the [Metrics & Monitoring document](monitoring.md).

### BGP Service

This component provides sets of interfaces for interacting with an external
program which handles speaking BGP (border gateway protocol).  It is explained
at length in the [BGP document](midolman-bgp.md).
