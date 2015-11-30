# IPSec traffic in the MidoNet Agent

This document describes how IPSec traffic traverses the MidoNet Agent.

# Overview

MidoNet supports tunnelling traffic between subnets using
IPSec. However, MidoNet does not implement the IPSec and IKE protocols
itself. Instead, it runs an [IKE][1] daemon in a linux namespace. The IKE
daemon negotiates [Security Associations (SA)][2] with a remote
endpoint, and installs them into the IP stack of the namespace, along
with a policy to apply the SA to traffic going through the IP stack.

The namespace is attached to a virtual router. The IKE endpoint is
configured to use a public IP address of a virtual router. The
resulting SAs have the router public IP as one endpoint of their tunnels.

Cleartext traffic from the VMs on the local private subnet to the
remote private subnet is redirected to the IPSec namespace where it is
encrypted and encapsulated in a tunnel packet.

Encrypted traffic for the local private subnet from the remote private
subnet arrives at the public interface of the virtual router and is
redirected to the IPSec namespace where it is deencapsulated and
decrypted before being forwarded to the local private subnet.

```
                       |
            Public     |
            Network    |
                       |
                       |
                     --+--
                    /\   /\     +-----------------+
          Virtual  /  \ /  \    |                 |
          Router   |   \   +----+ IPSec Namespace |
                   \  / \  /    |                 |
                    \/   \/     +-----------------+
                     --+--
                       |
           Local       |
       Private Subnet  |
     +--------+--------+--------+--------+
     |        |        |        |        |
   +-+--+   +-+--+   +-+--+   +-+--+   +-+--+
   |    |   |    |   |    |   |    |   |    |
   | VM |   | VM |   | VM |   | VM |   | VM |
   |    |   |    |   |    |   |    |   |    |
   +----+   +----+   +----+   +----+   +----+

```

The rest of this document describes how traffic is redirected into the
namespace for encryption/decryption.

# IPSec namespace setup

For the namespace to be able to receive traffic destined for the
router's public interface, it must believe that it is the correct
destination for the traffic.

This can be achieved by setting the MAC address and IP address of the
namespace's interface to be the same as the MAC address and IP address
of the public interface of the router.

The namespace also needs to be able to receive traffic which is not
destined for the router's public IP. For example, cleartext traffic
to the remote private subnet must be routed to the namespace so that
it can be encrypted and tunnelled.

For the virtual router to be able to route traffic to the namespace,
the namespace needs to be addressable from the virtual router. Traffic
to the namespace cannot be routed to the router public ip, because
this address already exists on another router port. The namespace
needs another address, to which traffic can be routed. For this, the
namespace interface has a link-local address with a /30 prefix. The
other address from this prefix is assigned to virtual router port
which is bound to the namespace.

All traffic leaving the namespace must to be routed via the
link-local address. To ensure this, the router public IP assigned to
the namespace interface should have a /32 prefix.

Traffic leaving the namespace which has its source IP set to the
router public IP, may ARP for the namespace's router port, using the
router public IP. This ARP request will never get a
response. Therefore, the namespace must have a static ARP entry so
that it never arps for the MAC of the namespace's router port.

## Example setup

In this example, there is a tenant router with public ip
201.201.201.1 on port0. A namespace is attached to router port1.

The router has the following ports:

```
port port0 device router1 state up plugged no mac ac:ca:ba:4a:61:c5 address 201.201.201.1 net 201.201.201.1/24
port port1 device router1 state up plugged no mac ac:ca:ba:43:05:45 address 169.254.1.1 net 169.254.1.0/30
```

The namespace's interface is configured as follows.

```
35: vpnc99b23de-a9ns: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
    link/ether ac:ca:ba:4a:61:c5 brd ff:ff:ff:ff:ff:ff
    inet 169.254.1.2/30 scope global vpnc99b23de-a9ns
       valid_lft forever preferred_lft forever
    inet 201.201.201.1/32 scope global vpnc99b23de-a9ns
       valid_lft forever preferred_lft forever
```

The namepace has the following routes:

```
default via 169.254.1.1 dev vpnc99b23de-a9ns
169.254.1.0/30 dev vpnc99b23de-a9ns  proto kernel  scope link  src 169.254.1.2
```

The namespace has the following static arp mappings:

```
Address                  HWtype  HWaddress           Flags Mask            Iface
169.254.1.1              ether   ac:ca:ba:43:05:45   CM                    vpnc99b23de-a9ns
```

# Redirecting incoming traffic

To redirect incoming traffic destined for the router port, we use
chain rules. The incoming traffic for IPSec is either tunnelled
encrypted traffic([ESP][3]) or IKE traffic.

There is a chain rule for each type of traffic redirected to the
namespace. For IPSec, there is a rule for ESP(network protocol 50)
traffic and a rule for IKE traffic(udp port 500).

The rule type is l2-transform and the action is redirect. The redirect
points to the port bound to the namespace.

For example, to redirect IPSec traffic to the namespace described
previously, the following two rules are needed.

```
midonet> chain chain0 rule add type l2-transform dst 201.201.201.1/32 proto 50 action redirect target-port router1:port0
midonet> chain chain0 rule add type l2-transform dst 201.201.201.1/32 proto 17 dst-port 500 action redirect target-port router1:port0
```

The chain with these rules should be set as the local redirect chain
of the router.

```
midonet> router router0 set local-redirect-chain chain0
```

# Redirecting outgoing traffic

Redirecting outgoing traffic to the namespace is simply a matter of
routing. Traffic from the local subnet to the remote subnet is routed
to the namespace's port via the namespace's link local address.

For example, if the local subnet is 192.168.10.0/24 and the remote
subnet is 192.168.20.0/24, we add the following route.


```
midonet> router router0 add route type normal src 192.168.10.0/24 dst 192.168.20.0/24 gw 169.254.1.2 port router:port1
```



[1]: https://tools.ietf.org/html/rfc4306 "Internet Key Exchange (IKEv2) Protocol"
[2]: https://tools.ietf.org/html/rfc4301 "Security Architecture for the Internet Protocol"
[3]: https://tools.ietf.org/html/rfc4303 "IP Encapsulating Security Payload (ESP)"
