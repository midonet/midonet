## Introduction

Midolmanj is the controller for the *MidoNet* SDN system.

## Subsystems

There are multiple logical components inside the *MidoNet* controller that are
discussed below

### Monitoring

This component handles the collection and export of the metrics gathered by the
system. It is explained at length inside the [Metrics & Monitoring document](monitoring.md).

### PortService

This component provides sets of interfaces that can be also used for
supporting various network services.  It is explained at length inside the
[PortService document](midolman-portservice.md).
