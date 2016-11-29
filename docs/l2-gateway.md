# L2 Gateway

## Motivation

Connect MidoNet virtual bridges and routers to physical L2 segments
where VLAN tags are widely used to provide L2 isolation in the cloud.
In addition to this, this should at least provide a failover capability
with 2 virtual ports which could be bound with ports on different hosts.

## Bridge behaviour

The Bridge interior port has an *optional* property `vlan_id` used to
connect a Vlan-Aware bridge to a Vlan-Unaware bridge. Bridge exterior
ports are used as Trunks. Also, the API now allows connecting interior
bridge ports, as long as one of the two has a "vlan-id" tag in it.

The Bridge will keep all it's previous behaviour and perform MAC
learning. The presence of vlan-ids in frames will imply the following
changes:
- Flood actions will include the interior port tagged with the
  frame's vlan-id (if exists). The frame going into the interior port
  will also get the vlan-id POP'd.
- For Frames with a vlan-tag, the Bridge will require that the port
  matched in the MAC table matches the port that is tagged with the
  frame's vlan-id. Otherwise, it'll drop the frame.

Again, the multicast on a Vlan-Aware Bridge case requires the usage of
a simulation fork (the simulation results in a ToPort action to all
exterior ports, plus a ToPort towards the VUB). This is similar to the
case described above (a multicast at the VUB), however, in that case
the PacketContext's match is modified removing the vlan-id as a result
of the second simulation in the VAB. But here, the PacketContext
modification happens on the first simulation (the VAB is responsible
to POP the vlan) so the Coordinator would apply the FlowActionPopVLAN
*before* the frame floods the Bridge (through the trunks). Which is
incorrect. This must happen only *after* the FloodBridge is processed.

Note that during loops it's likely that frames may bounce back from the
physical switch into Midolman, causing conflicting mac-port mappings.
As a consequence of this, frames ingressing from one port may be
forwarded back to the same port after mac-learning. Midolman will detect
this case and create a temporary drop, giving time for the network to
stabilize the mac on a given port.
