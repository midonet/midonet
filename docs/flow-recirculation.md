## Overview

Some virtual devices, like VTEP routers, encapsulate packets. While
this is handled trivially in userspace during the simulation, we must configure 
the kernel to encapsulate or decapsulate packets without userspace help. Since 
the datapath does not support this feature, we must recirculate packets through 
the host stack: for encapsulation, packets are emitted from a datapath tunnel 
port and routed through the host stack back to the datapath for additional 
transformations, where they now appear as normal UDP packets; for 
decapsulation, packets are emitted through a normal netdev port such that the 
host stack routes them to the datapath's tunnel port, which exposes the inner 
packet.

To do this, the agent creates a `veth` pair in the host, connecting one end to 
the datapath. It also defines an additional tunnel port to carry out 
encapsulation and decapsulation. The following diagram represents the 
recirculation mechanism, showing some default values.

```
                    +----------+
                    |   Host   |
                    +----------+             
              VXLAN   ^      ^   midorecirc-host
              8899    |      |   169.254.123.1
                      v      |
              +-------*--+   |
              | Datapath |   |   veth pair
              +-------*--+   |
                      ^      |  
      midorecirc-dp   |      |
    (169.254.123.2)   +------+
```

Note that only the host's `veth` endpoint is assigned an IP address. The .2
address, while not actually assigned to the datapath's `veth` endpoint, is where 
we address packets that we want routed to that endpoint. In this document we
refer to it as the address of the datapath's `veth` endpoint as simplification. 

Packets addressed from .1 to .2 are routed by the host stack through the
`midorecirc-host` endpoint, and they enter the datapath through the
`midorecirc-dp` datapath port. We use this for encapsulation.

Packets addressed from .2 to .1:8899 are routed by the host stack to the
datapath's 8899 VXLAN tunnel port. We use this for decapsulation.

The rest of the document explains some simulation details about how encap and 
decap operations employ this mechanism.

### General approach

Encapsulation and decapsulation both happen in the middle of the simulation. So,
one approach would be to end the simulation at the encap/decap boundary, install
a flow, execute the packet so it is sent back again to userspace, then carry out
a second simulation using the encaped/decaped packet. This approach has a couple 
of problems: it requires two trips to userspace and four datapath operations 
(two flow creates and two packet executions), and it would require that flow 
state be somehow threaded between the first simulation, where it is generated, 
and the second simulation, where it is actually forwarded to the egress host.

To solve this, we do everything in the context of one simulation, installing two
flows at the end. The simulated packet is encapsulated/decapsulated during the 
simulation and at the end it is executed with the actions generated after
encapsulation/decapsulation. Subsequent packets match both flows in the kernel.

### Encapsulation

Encapsulation is performed by calling `PacketContext::encap()` with a
VXLAN VNI and an outer header (MAC, IP and transport sources and destinations).
This will calculate the set of actions to apply to the inner packet, and will
encapsulate it. We also add a virtual `Encap` action containing the VNI to the 
virtual actions list. The simulation then proceeds over a `FlowMatch`
identifying the outer header.

During flow translation, when the `Encap` action is encountered, the
`PacketContext`'s `recircFlowActions` will contain all the actions to apply to
the packet before it is encapsulated. It is now the job of flow translation to 
add the necessary actions to make the datapath encapsulate the packet and
recirculate it through the host stack.

We do this by adding a tunnel flow action with the encap VNI and with the host's 
`veth` endpoint as source address and the datapath's `veth` endpoint as destination
address. This way, when the packet is emitted out of the tunnel port, it will
appear to the host as being directed from one side of the veth endpoint to the
other. The stack will then deliver the packet back to the datapath, where it 
appears as a normal UDP packet. These actions will comprise one of the flows we
install, the one matching the original packet.

Note that the outer headers -- the MAC, IP, and transport sources and
destinations -- will not match the ones specified in the call to
`PacketContext::encap()`. To correct this, we add `SetKey` flow actions to the 
`PacketContext`'s flow and packet actions -- these lists will contain the actions 
to apply to the encapsulated packet as flow translation then proceeds as normal.
Those actions will be part of the second flow that matches the outer headers.

Note that during flow installation, we install the flow matching the outer
headers first. This ensures all packets of the flow received in the meanwhile go
to userspace instead of being misshandled. 

### Decapsulation

Decapsulation is performed by calling `PacketContext::decap()` with the inner
packet -- obtained by parsing the VXLAN packet -- and the VNI. Unlike with 
`encap()`, we don't need to calculate any virtual flow actions as the outer 
packet is simply discarded for all intents and purposes. We just add a virtual 
`Decap` action containing the VNI to the virtual actions list. The simulation 
then proceeds over a `FlowMatch` identifying the inner headers.

During flow translation, the `Decap` action is the first action to be encountered.
We change the packet's outer header by adding `SetKey` flow actions to 
`recircFlowActions`, which will make it look like the packet is being sent from 
the datapath's veth endpoint to the host's endpoint, and we change the UDP 
destination port to match the one of the recirculation VXLAN port. This causes 
the packet to appear directed at the host, which the stack deliveres to the 
datapath based on the UDP port. We add an `OutputFlowAction` to emit the packet 
from the datapath's veth endpoint. These actions comprise one of the flows we
install, the one matching the original packet.

Unlike with encapsulation, we don't need to transform the packet, as the
datapath will correctly see the inner packet headers when the packet is received
by the tunnel port and decapsulated. So, the only actions in the flow matching
the decapsulated packet are the ones introduced naturally by the simulation.

### Uniqueness

With the datapath flows installed, packets belonging to a given flow are 
encapsulated according to these steps:
1.  The packet matches a flow that applies some transformations and 
    recirculates it by outputting the packet from a tunnel port;
2.  The tunnel headers are set to statically configured values;
3.  Based on these values, the host stack routes the packet to the host's veth
    endpoint;
4.  The packet re-enters the datapath from the other veth endpoint;
5.  It matches a flow that sets the outer packet headers to values defined by the
    simulation and specific to the flow;
6.  The packet is output to the egress host.

Similarly, packets are decapsulated with the following steps:
1.  The packet matches a flow that recirculates it by outputting the packet from
    the datapath's veth endpoint;
2.  The flow also modifies the outer packet headers to statically configured values;
3.  Based on these values, the host stack routes the packet to the tunnel port;
4.  The packet re-enters the datapath and is decapsulated. 
5.  It matchs a flow specific to the inner headers;
6.  The packet is output to the egress host.

In both cases, the second step causes a packet with particular header values to 
appear on the host, so it can be consistently routed to the correct endpoint,
no matter what flow it belongs to; that is, any two packets end up looking the 
same. Since these packets must uniquely match a second flow, that contains 
specific actions to apply to either the outer or inner headers, we must impart 
some uniqueness to the packet.

We do this by using the TOS and TTL fields of the outer packet, which allows for
64k unique pairs of flows to be installed. Flow translation is where we assign a
unique value to these fields.

Future work should increase this number. This is easy to do for decapsulation,
where the source address need not be exactly the datapath's veth endpoint (the 
.2 address in the diagram above): it can be any address in that subnet. This 
way we gain an extra 32 bits of uniqueness. For encapsulation, the source 
address must be the host's veth endpoint address, as any other value will cause
the packet to be misrouted. This is solved by adding a route to the kernel
matching addresses on the recirculation subnet and sending them via
`midorecirc-host`.

