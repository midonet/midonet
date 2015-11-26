# IPSec traffic in the midonet agent

This document describes how IPSec traffic traverses the midonet agent.

# Overview

Midonet supports tunnelling traffic between subnets using
IPSec. However, Midonet does not implement the IPSec and IKE protocols
itself. Instead, it runs an IKE[1] daemon in a linux namespace. The IKE
daemon negotiates Security Associations (SA) [2] with a remote
endpoint, and installs them into the IP stack of the namespace, along
with a policy to apply the SA to traffic going through the IP stack.

The namespace is attached to a tenant router. The IKE endpoint is
configured to use the public IP address of the tenant
router. The resulting SAs have the tenant router public IP as one
endpoint of their tunnels.

The rest of this document describes how traffic is redirected into the
namespace for encryption/description.

# IPSec namespace setup

For the namespace to be able to receive traffic destined for the
router's public interface, it must believe that it is the correct
destination for the traffic.

This can be achieved by setting the MAC address and IP address of the
namespaces interface to be the same as the MAC address and IP address
of the external interface of the router.

The namespace also needs to be able receive traffic which is not
destined for the router's public IP. For example, cleartext traffic
to the remote subnet needs to be routed to the namespace so that it
can be encrypted and tunnelled.

To be able to route to the namespace, the namespace needs an IP which
is not the same as the router's public IP. For this the namespace
interface is given a link-local address, with a /30 subnet. The router
port which is attached to the namespace is given the other address
from the namespace.

We want all traffic leaving the namespace to be routed via the
link-local address. To ensure this, the router public IP assigned to
the namespace interface should have a /32 prefix.

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

# Redirecting incoming traffic

To redirect incoming traffic destined for the router port, we use
chain rules. The incoming traffic for IPSec is either tunnelled
encrypted traffic(ESP[3]) or IKE traffic.

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

# References

[1] "Internet Key Exchange (IKEv2) Protocol" https://tools.ietf.org/html/rfc4306
[2] "Security Architecture for the Internet Protocol" https://tools.ietf.org/html/rfc4301
[3] "IP Encapsulating Security Payload (ESP)" https://tools.ietf.org/html/rfc4303
