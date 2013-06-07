# L2 Gateway

## Motivation

Connect MidoNet virtual bridges and routers to physical L2 segments
where VLAN tags are widely used to provide L2 isolation in the cloud.
In addition to this, this should at least provide a failover capability
with 2 virtual ports which could be bound with ports on different hosts.

## VlanAwareBridge implementation (DEPRECATED)

L2 Gateway was implemented originally with a new device
"VlanAwareBridge" (henceforth VAB). This incorporated two new types of
ports to the Midonet API (exterior "trunk ports" and "vlan-aware
interior ports"). This device included very limited bridging
capabilities (i.e.: no MAC learning).

The VAB is defined as a L2 device that can have exterior ports (trunks)
and interior ports connecting to a normal vlan-unaware Bridge (VUB). The
VAB-interior port must be tagged with vlan-id; each vlan-id can be
present at most in one interior port. A VUB connected to a VAB via a
port tagged with vlan X will be considered to be on vlan X (as well as
all devices connected to this VUB). Also, a VUB can only be connected to
a single VAB.

The behaviour is as follows:

- BPDU frames are forwarded accross trunks (only 802.1D).
- Frames from a trunk with a vlan-id:
  - If the vlan id is present in one of the interior ports the vlan-tag
    will be POP'd and the frame forwarded to the peer. If the frame has
    a broadcast dst address it'll only be sent to the interior port, not
    to the other trunks.
  - If the vlan id is not present among the interior ports, it'll be
    dropped.
- Frames from a trunk with no vlan-id present will be dropped.
- Frames from an interior port will have the vlan-id corresponding to
  that port PUSH'd, then the frame will be sent to all trunks.
  - Note that unaware-bridges will include the interior port connecting
    to a VAB in the port set when broadcasting a frame. In this case,
    the VAB is expected to behave as described: push the vlan id and
    send to all trunks.
  - Note that this implementation allows QinQ: if VMs connected to the
    unaware-bridge send vlan-tagged frames, the port's vlan-id will get
    stacked on top of it.

The decission to send frames from the virtual network to all trunks is
made under the assumption that as a result of STP operation among the
bridges only one of the trunk ports will be active. In a failover
scenario, the active trunk will change but the VAB can remain agnostic
of this detail by transmitting to all the trunks.

## Coordinator changes

Supporting multicast from VUB's connected to a VAB required an
additional change in the Coordinator. Note that in a typical topology, a
VUB linked to a bunch of VMs will be connected to a single VAB. A
multicast frame (e.g.: an ARP request from a VM) will need to send the
frame to its PortSet, but also to the VAB in order to create L2
connectivity towards the trunks. This is achieved by generating a new
type of action ForkAction, that contains a list of actions to be
executed sequentially: ToPortSet (locally on the VUB) and a ToPort
(towards the VAB).

Note also that after the second simulation is executed on the VAB, it
will result in a second ToPortSet on the VAB (sending to all the
trunks), that will include a PUSH VLAN action. This has implications on
the Flow that should result from the simulation since we'll need to
ensure that the output actions to the ports connected to the VUB is made
before the PUSH VLAN action. The output actions to the VAB trunks should
happen after the PUSH is executed.

### Bridge implementation

After the initial POC the vlan-aware bridge needed to be improved with
MAC-learning and other functionality already present in the Bridge. In
order to do this, two options were evaluated.
- Refactor the Bridge code to decouple MAC-learning, etc. from it in
  order to enable sharing with the VAB
- Deprecate the VAB and have the Brige support vlan-awareness + BPDU
  forwarding.

The second option required approximately the same development cost,
whereas it allowed getting rid of additional API entities + methods.
Note that the VlanAwareBridge implementation is deprecated and only the
Bridge should be used to create L2Gateways in virtual topologies.

In order to meet the requirements the Bridge interior port received a
new *optional* property `vlan_id`. Bridge exterior ports are used as
Trunks. Also, the API now allows connecting interior bridge ports, as
long as one of the two has a "vlan-id" tag in it.

The Bridge will keep all it's previous behaviour and perform MAC
learning. The pressence of vlan-ids in frames will imply the following
changes:
- ToPortSet actions will include the interior port tagged with the
  frame's vlan-id (if exists). The frame going into the interior port
  will also get the vlan-id POP'd.
- For Frames with a vlan-tag, the Bridge will require that the port
  matched in the MAC table matches the port that is tagged with the
  frame's vlan-id. Otherwise, it'll drop the frame.

Again, the multicast on a vlan-aware Bridge case requires the usage of
the ForkAction (the simulation results in a ToPortSet, plus a ToPort
towards the VUB). This is similar to the case described above (a
multicast at the VUB), however, in that case the PacketContext's match
is modified removing the vlan-id as a result of the second simulation in
the VAB. But here, the PacketContext modification happens on the first
simulation (the VAB is responsible to POP the vlan) so the Coordinator
would apply the FlowActionPopVLAN *before* the frame get's sent from the
PortSet (to the trunks). Which is incorrect. This must happen only
*after* the ToPortSetAction is processed.

This required further changes in the ForkAction that now supports a new
type of action, DoFlowAction. When the VAB simulates the multicast,
it'll return a ForkAction containing -in this order- a ToPortSetAction,
a DoFlowAction(FlowActionPopVLAN), and a ToPortAction. When the
Coordinator processes the DoFlowAction it'll add an
AddVirtualWildcardFlow to the list of results from each part of the
fork in the right orden, and also modify the WildcardMatch in the
PacketContext so that the second simulation (on the VUB) doesn't see the
vlan-id in the match. When the results are merged, the output on the
VAB's trunk retains the vlan-id, but the outputs generated by the VUB
won't.
