# Device behaviour

## Motivation

This document provides high level information about Midonet device's
implementation behaviour. It is intended mainly to highlight points
where expected behaviour from their physical counterparts diverges or
requires taking into account special considerations.

## Bridges

### VLAN Awareness

Bridges are considered "VLAN aware" when an interior port is tagged with
a VLAN id. As described in the REST API Specification document, VLAN
tagged interior ports can only be linked to other Bridge's interior
ports which must *not* be VLAN tagged. Refer to the l2-gateway
document for more details on VLAN Aware bridges.

### Rule Processing with VLAN traffic

Bridges in Midonet will *not* apply pre and post rule chains on frames
carrying a VLAN tag. The reasoning for this is that rules expect to have
access to fields in all layers, yet a Bridge should not dig into fields
encapsulated inside a VLAN tag. For example, we could set rules
examining/modifying layer 3 fields, but a Bridge should not be able to
see these on a VLAN tagged frame.

If applying rules on this traffic is required, the recommended setup is
to place a VLAN Aware Bridge in front of the Vlan Unaware Bridge where
rules are to be set. The VAB would have a port tagged with vlan X and
connected to the Bridge. This way, the VAB will be responsible for
popping the VLAN tag in the frame. When this reaches the VUB the payload
will be naked for rules to examine it.
