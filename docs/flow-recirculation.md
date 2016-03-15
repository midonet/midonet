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
`PacketContext::encap()`, instead being the values passed to the tunnel flow
action. To correct this, we add `SetKey` flow actions to the
`PacketContext`'s flow and packet actions -- these lists will contain the actions
to apply to the encapsulated packet as flow translation then proceeds as normal.
Those actions will be part of the second flow that matches the outer headers.

During flow installation, we install the flow matching the outer headers first.
This ensures all packets of the flow received in the meanwhile go to userspace
instead of being mishandled.

Consider the following example, in which the order of the flows isn't related
to their order of installation:

```
  Flow
    Match: FlowMatch[InputPortNumber=2, EthSrc=ac:ca:ba:c9:67:5d, EthDst=ac:ca:ba:5a:1e:0d, EtherType=0800, NetworkSrc=44.128.136.46, NetworkDst=22.118.139.248, NetworkProto=tcp, NetworkTTL=64, NetworkTOS=0, FragmentType=None, SrcPort=123, DstPort=456]
    Mask:  FlowMask[Priority{0}, InPort{-1}, Ethernet{src=ff:ff:ff:ff:ff:ff, dst=ff:ff:ff:ff:ff:ff}, EtherType{0xffffffff}, KeyIPv4{src=255.255.255.255, dst=255.255.255.255, proto=-1, tos=-1, ttl=-1, frag=-1}, TCP{src=65535, dst=65535}]
    Actions:
      SetKey{TCP{src=9876, dst=80}}
      SetKey{Tunnel{tun_id=4624, ipv4_src=169.254.123.1, ipv4_dst=169.254.123.2, tun_flag=0, ipv4_tos=0, ipv4_ttl=0}}
      Output{port=4}
  Flow
    Match: FlowMatch[InputPortNumber=5, EthSrc=00:00:00:00:00:00, EthDst=00:00:00:00:00:00, EtherType=0800, NetworkSrc=169.254.123.1, NetworkDst=169.254.123.2, NetworkProto=udp, NetworkTTL=0, NetworkTOS=0, FragmentType=None, SrcPort=0, DstPort=8899]
    Mask:  FlowMask[Priority{0}, InPort{-1}, Ethernet{src=00:00:00:00:00:00, dst=00:00:00:00:00:00}, EtherType{0xffffffff}, KeyIPv4{src=255.255.255.255, dst=255.255.255.255, proto=-1, tos=-1, ttl=-1, frag=-1}, UDP{src=0, dst=65535}, Tunnel{tun_id=0, ipv4_src=0.0.0.0, ipv4_dst=0.0.0.0, tun_flag=0, ipv4_tos=0, ipv4_ttl=0}]
    Actions:
      SetKey{Ethernet{src=ac:ca:ba:d2:ce:db, dst=ac:ca:ba:1d:1c:e8}}
      SetKey{UDP{src=38773, dst=4789}}
      SetKey{KeyIPv4{src=82.81.31.192, dst=224.153.166.35, proto=17, tos=0, ttl=12, frag=0}}
      Output{port=1}
```

The first flow applies to the original packet. Note that it's a specific match:
this is a requirement since this flow applies only to part of the simulation.
The flow transforms the inner packet's transport headers and encapsulates the packet
with the source IP address set to `midorecirc-host` and the destination set to the
datapath `veth` endpoint.

The second flow matches on these values, as can be seen by the flow mask, and
also on the recirculation VXLAN tunnel port UDP destination. It then sets the
MAC, IP and transport headers to what was passed to `encap()` and outputs the
packet to the egress datapath port.

### Decapsulation

Decapsulation is performed by calling `PacketContext::decap()` with the inner
packet -- obtained by parsing the VXLAN packet -- and the VNI. Unlike with
`encap()`, we don't need to calculate any virtual flow actions to apply to the
outer packet (nor should any really exist), because decapsulating a packet
means shedding the outer headers. We just add a virtual `Decap` action
containing the VNI to the virtual actions list. The simulation then proceeds
over a `FlowMatch` identifying the inner headers.

During flow translation, the `Decap` action is the first action to be encountered.
We change the packet's outer header by adding `SetKey` flow actions to
`recircFlowActions`, which will make it look like the packet is being sent from
the datapath's veth endpoint to the host's endpoint, and we change the UDP
destination port to match the one of the recirculation VXLAN port. This causes
the packet to appear addressed at the host, which the stack delivers to the
datapath based on the UDP port. We add an `OutputFlowAction` to emit the packet
from the datapath's veth endpoint. These actions comprise one of the flows we
install, the one matching the original packet.

Unlike with encapsulation, we don't need to transform the packet, as the
datapath will correctly see the inner packet headers when the packet is received
by the tunnel port and decapsulated. So, the only actions in the flow matching
the decapsulated packet are the ones introduced naturally by the simulation.


Consider the following example:

```
  Flow
    Match: FlowMatch[InputPortNumber=2, EthSrc=ac:ca:ba:24:53:86, EthDst=ac:ca:ba:b6:d9:bf, EtherType=0800, NetworkSrc=111.95.198.244, NetworkDst=67.228.68.117, NetworkProto=udp, NetworkTTL=64, NetworkTOS=0, FragmentType=None, SrcPort=59544, DstPort=4789]
    Mask:  FlowMask[Priority{0}, InPort{-1}, Ethernet{src=ff:ff:ff:ff:ff:ff, dst=ff:ff:ff:ff:ff:ff}, EtherType{0xffffffff}, KeyIPv4{src=255.255.255.255, dst=255.255.255.255, proto=-1, tos=-1, ttl=-1, frag=-1}, UDP{src=65535, dst=65535}, Tunnel{tun_id=0, ipv4_src=0.0.0.0, ipv4_dst=0.0.0.0, tun_flag=0, ipv4_tos=0, ipv4_ttl=0}]
    Actions:
      SetKey{Ethernet{src=02:00:11:00:11:02, dst=02:00:11:00:11:01}}
      SetKey{KeyIPv4{src=169.254.123.2, dst=169.254.123.1, proto=17, tos=0, ttl=0, frag=0}}
      SetKey{UDP{src=12345, dst=8899}}
      Output{port=5}
  Flow
    Match: FlowMatch[InputPortNumber=4, TunnelKey=4624, TunnelSrc=169.254.123.2, TunnelDst=169.254.123.1, TunnelTOS=0, TunnelTTL=0, EthSrc=ac:ca:ba:6e:bc:e7, EthDst=ac:ca:ba:65:69:a3, EtherType=0800, NetworkSrc=163.147.121.174, NetworkDst=213.244.85.174, NetworkProto=tcp, NetworkTTL=64, NetworkTOS=0, FragmentType=None, SrcPort=123, DstPort=456]
    Mask:  FlowMask[Priority{0}, InPort{-1}, Ethernet{src=00:00:00:00:00:00, dst=00:00:00:00:00:00}, EtherType{0xffffffff}, KeyIPv4{src=0.0.0.0, dst=0.0.0.0, proto=-1, tos=0, ttl=0, frag=0}, TCP{src=0, dst=0}, Tunnel{tun_id=-1, ipv4_src=255.255.255.255, ipv4_dst=255.255.255.255, tun_flag=0, ipv4_tos=-1, ipv4_ttl=-1}]
    Actions:
      SetKey{TCP{src=9876, dst=80}}
      Output{port=1}
```

The first flow matches the outer packet as a normal UDP packet. It matches the 
values passed to `encap()` and it applies the necessary transformations to 
recirculate the packet from the datapath's `veth` endpoint to the tunnel port, 
including setting the destination UDP port to 8899.

The second flow matches the tunnel headers, including the VNI (4624), which are
the values the previous flow changes on the outer packet. The flow actions change
the transport ports (because the simulation of the inner packet so determined) and
outputs the inner packet to the egress datapath port.  

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

