## Introduction

Midolmanj is the controller for the *MidoNet* SDN system.

## Subsystems

There are multiple logical components inside the *MidoNet* controller that are
discussed below

### Monitoring

This component handles the collection and export of the metrics gathered by the
system. It is explained at length inside the [Metrics & Monitoring document](monitoring.md).

### BGP Service

This component provides sets of interfaces for interacting with an external
service which handles speaking BGP (border gateway protocol).  It is explained
at length in the [BGP document](midolman-bgp.md).
