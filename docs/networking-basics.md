<!---
Copyright 2012 Midokura Inc.
Generate HTML with:
        markdown_py networking-basics.md -f networking-basics.html -x toc
-->

[TOC]

# Computer networks


## Basic concepts

The utility of computers is greatly increased when they have the ability
to communicate with one another.  This ability is called *networking*
and the computers so communicating and the equipment enabling that
communicating are individually called *nodes* and collectively together
form a *computer network*, or just *network*.  The data sent through a
computer network is called *traffic*.  Nodes can be connected by means
such as cables, radio signals, or in the case of virtual networking,
software abstractions.  These direct connections between nodes are
called *links*.  Networks are often represented as a graph, with boxes
representing the nodes and lines connecting the boxes representing links.

Computers which wish to communicate through the network are typically not
directly connected, that is, they don't share a link, and so for them to
communicate the network equipment must pass the data from node to node
over links along a path from the sending node to the receiving node.
This "passing along" is called *forwarding*, and Midonet uses the
generic term *forwarding element* to denote a node which participates
in forwarding.  A node which doesn't participate in forwarding is a
*leaf node*, and often will have a single link.  Leaf nodes typically
provide and consume the vast bulk of network traffic, with forwarding
elements usually generating and consuming only traffic relating to the
administration and control of the network.

In order for the computers to "understand" the data they receive,
they must share conventions on what signals denote which data values,
how each computer is identified, how distinct "conversations" are
distinguished, etc.  These shared conventions are called *protocols*.

## OSI network layers

Protocols are layered, and the layer of a protocol is described by its
place in the *OSI model*, produced by ISO's Open Systems Interconnections
group.  From bottom to top, the layers are:

- Layer 1 or physical layer:
    This describes how signals are translated into digital values.
- Layer 2 or data link layer:
    This describes how traffic is sent over a link.
- Layer 3 or network layer:
    This describes how traffic is forwarded over a network.
- Layer 4 or transport layer:
    This describes how data streams are segmented and multiplexed.
- Layer 5 or session layer:
    This describes how connections are started, stopped, and managed.
- Layer 6 or presentation layer:
    This describes how the data sent is encoded.
- Layer 7 or application layer:
    This describes end-user relevant data.

These layers shouldn't be taken too seriously:  They don't have clear
boundaries, and many protocols span layer boundaries.  The OSI model
is a guide to use when describing the responsibilities of a protocol,
not a rigid mold to fit protocols into.

Midonet primarily focuses on layers 2 and 3.  The layer 2 protocol
Midonet uses is *Ethernet* and the layer 3 protocol is *IPv4*.

## Ethernet

Ethernet spans layers 1 and 2, specifying the interpretation of signals
as sequences of bytes in discrete chunks called *frames*.  The largest
acceptable frame size supported on an Ethernet network is that network's
*MTU* (maximum transmission unit).  A set of links which share the same
Ethernet traffic is called an *Ethernet segment*.  Ethernet segments are
enlarged with forwarding elements which broadcast all traffic they receive
on any link out all their other links -- these are called *Ethernet hubs*.

Because an Ethernet segment may contain traffic for multiple nodes,
Ethernet frames begin with a pair of 6-byte addresses called *MAC
addresses* to identify the sender and recipient.  MAC addresses
consist of a three byte *OUI* (Organizationally Unique Identifier)
assigned by the IEEE to companies building network equipment, and three
bytes of "extension identifier" assigned by the company, to compose a
hopefully-unique address for every network interface.  MAC addresses are
written as six colon-separated byte values, each byte value written as two
hexadecimal digits, for example `08:00:07:a9:b2:fc`.  Midokura's OUI is
`AC:CA:BA`.

The part of a computer which is responsible for digitizing, framing, and
address-checking Ethernet traffic is the *NIC* (network interface card).
A NIC which doesn't address-check (and so receives all traffic on the
segment, even that intended for a different recipient) is said to be
operating in *promiscuous mode*.

A forwarding element which knows which Ethernet addresses are on which
links is called an *Ethernet switch*.  A switch typically determines the
link of an address by remembering the link used for a frame with that
address as its sender address -- this is called *learning*.  A switch
which hasn't learned an address it's asked to send to falls back to
behaving like a hub, and broadcasting the traffic.  Switches break an
Ethernet network into multiple segments, and an Ethernet network where
every segment is a single link is called *fully switched*.

Two nodes which have only Ethernet hubs and switches between them can
communicate with only layer 2 used in forwarding -- this is called being
in a *layer 2 domain* (*L2 domain* for short).  The reliance of layer
2 equipment on broadcasting makes them vulnerable to forwarding traffic
into infinite loops when an L2 domain contains a cycle.  To prevent this,
Ethernet switches typically implement *STP* (spanning-tree protocol),
which disables broadcast traffic over certain links of an L2 domain,
the non-disabled links forming a spanning tree (graph with no cycles
and connecting all nodes) over the nodes of the domain.  Ethernet hubs
typically do not implement STP, therefore a cycle containing a hub
re-introduces the vulnerability to infinite looping.  Because of this,
hubs are typically deployed with one link to the rest of the network,
and all other links connected to leaf nodes.  Midonet's switches (at
this time) also don't implement STP, so should be treated like hubs and
have only one of their links not connected to a leaf node.

## Internet protocol

To send traffic across L2 domains, more sophisticated layer 3 forwarding
is required.  The only layer 3 protocol in significant use at this time
is the *Internet protocol* or *IPv4* (four for historical reasons).
Internet traffic is divided into discrete chucks called *packets*;
packets are forwarded based on a four-byte address near the start of the
packet called the *IP address*.  IP addresses are written as their four
byte values in decimal, separated by dots, for example `10.255.0.5`.
This representation of an IP address is called a *dotted quad*.

Forwarding packets based on their IP address is called *routing*, and
the forwarding elements which do this are called *routers*.  To route
packets, a router will contain a *routing table*, which is list of
rules called *routes* that specify which packets go out which link.
A route consists of an address range, an optional next hop, and an
egress link.  An *address range* represents the set of all addresses
which have a certain number of leading bits in common.  Address ranges
are written in a notation called *CIDR syntax*, as a dotted quad, a
slash, and a number called the *prefix* which indicates how many bits a
matching address must have in common with the dotted quad; for example,
`192.168.1.128/25` represents the addresses from `192.168.1.128` to
`192.168.1.255`.  To decide which route to use for a packet, the routes
for address ranges containing the packet's address are compared, and the
one with the largest prefix is chosen.  This is called *longest prefix
matching*.  The address range `0.0.0.0/0` matches every IP address,
and a route for this range is called a *default route*, because it'll
match packets exactly when no other route does.

If an address range is contained inside another range, the smaller
range is called a *subnet* of the larger.  IP addresses are assigned in
a hierarchical basis, with a large provider giving subnets of a large
range it owns to its clients, who give smaller subnets of their own
subnet to their clients.  This way the organization of the IP address
space matches the organization of the network, at least somewhat, and
longest prefix matching can be used to forward packets toward their
intended destinations.  The highest numbered address of a subnet is
reserved for broadcast traffic to all nodes in that subnet.  Unless the
subnet is an isolated network with no outside connections, an address must
be used for each gateway connecting the subnet to an external network.
The other addresses in the subnet are available to be used by leaf nodes
or to form smaller subnets.

As an IPv4 packet is contained inside a layer 2 protocol frame, to deliver
packets to a node in the same L2 domain for which the sending node only
knows an IP address, not an L2 address, it must first discover the other
node's L2 address to use as the address in the outer layer 2 frame.
In the case of Ethernet, this discovery is accomplished using *ARP*
(address resolution protocol).  To find a MAC address with ARP, a node
sends a broadcast packet requesting the owner of the IP address of
interest to respond back with its MAC address.  If no reply is received
after several tries, traffic delivery is aborted with a "no route to
host" error.  If a reply is received, the node records the contents of
the reply in an *ARP cache* so it won't have to make an ARP request for
other packets for the same destination.

Routers use the "next hop" field of the route to determine the L2 address
to use on their egress link when they forward the packet.  If the next
hop is absent from the route, that indicates that the router is the last
hop, and it sends the packet using the final destination's L2 address,
discovering it (eg, with ARP) if necessary.  If a sequence of routers
have routes that each point to the next router in the sequence for some
address, then back to the first router, then that address has a *routing
loop*.  An example of such a misconfiguration is if Router 1 handles
Router 2's default traffic and thinks Router 2 handles `168.212.226.0/24`,
but Router 2 doesn't think it does.  This is represented in the routing
tables by Router 1 having a route to Router 2 for `168.212.226.0/24`,
but Router 2 not having entries for that range, and having a route for
`0.0.0.0/0` which points back to Router 1.  To keep such loops from going
indefinitely, IP packets have a *TTL* (time to live) field, which is
decremented by each router in the course of forwarding.  If this field
reaches zero, the packet is discarded with a "TTL expired" error.

