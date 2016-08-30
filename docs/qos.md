# QOS support

The initial support for QoS features in midonet will match what is
available for the Neutron Newton release. This is support for options:

1. Modify the DSCP field of IP packets ingressing from a given port.
2. Police ingress bandwidth for a given port.

These two features correspond to the Newton QOS rules described in [1].

## Overview

The implementation details of the two features (policing and dscp marking)
will be exactly the same for the neutron plugin and the translators. The
objects representing QoSPolicy, BandwidthLimitRule, and DscpRule will
be sent over from Neutron and stored as they are in ZK.  However, the
the midolman agent will handle the two features differently.

### DSCP Marking

The configured DSCP value on a port will not effect the simulation. That is,
the simulation won't prioritize traffic based on the DSCP value. However,
when a flow entry is eventually programmed in the datapath, the DSCP value
will be set to the Tunnel TOS field. This means that tunneled traffic coming
from a midolman node will have its DSCP field set to whatever the user has
configured for the ingress port.

This is what the work flow will look like:

1. The user will configure a port to have "dscp marking" with some value
   using Neutron's QoS extension.
2. The neutron midonet plugin will forward this request to the
   midonet-cluster.
3. The midonet-cluster will take these configuration requests and ensure
   that the information is put into zookeeper, making it accessible to
   the midolman agent.
4. The midolman agent will accept simulation requests from the configured
   port, and if the destination port is on another host, it will inform
   the openvswitch datapath that the tunneled packets should have the DSCP
   value configured by the user.

### Ingress Bandwidth Policing

Ingress policing configuration from the user involves two values:

rate - the maximum bandwidth
burst - the maximum amount of bytes that can be received instantaneously

Midonet will throttle traffic originating from a given port according to
these values by programming the linux kernel with the appropriate qos
constructs. This means that the dropped traffic will never enter the
simulation.

This is what the work flow will look like:

1. The user will configure a port with a rate and a burst using neutrons
   QoS extension.
2. The neutron midonet plugin will forward this request to the
   midonet-cluster.
3. The agent will listen for updates regarding locally bound ports with
   rate and burst information, and send a request to the linux kernel to
   configure the physical interface with the appropriate traffic control
   constructs. This request will go through the linux traffic control
   netlink interface.
4. The linux kernel will police traffic on any port with the appropriate
   traffic control configuration.


The code changes for both features are broken down into 3 main areas:
Neutron Plugin, Midonet Cluster, and Midolman.

## Neutron Plugin

The neutron plugin will simply pass along the information configured by
the user to the midonet cluster. This means that 3 objects will be sent
over: the Qos Policy, the Dscp Mark Rule, and the Bandwidth Limit Rule.

qos_policy {
    UUID id;
    String name;
    String description;
    bool shared;
}

dscp_mark {
    UUID id;
    INT dscp_mark;
}

bw_limit {
    UUID id;
    INT max_kbps;
    INT max_burst_kbps;
}

## Midonet Cluster

The Midonet Cluster will expose three new API endpoints, one for each object
These endpoints will accept POST, PUT, and DELETE requests. The POST and PUT
handles will accept the entire object as its content, and forward it to one
of three QOS translators. The DELETE request will accept a UUID.

The Network and Port midonet model will have a back-reference to the qos policy
id added to them.

Instead of creating additional midonet model objects to be used by the agent,
These objects will be used by the agent as they are. This means that the
translators will have very little work to do besides some basic validation.

## Midolman

### Ingress Policing

The Midolman changes for ingress policing will not have any simulation
changes. Instead, changes will be made to the DatapathController to listen
for changes in the Qos Policies and Qos Rules, associate them with locally
bound ports, and write netlink messages to a netlink interface created to
handle linux traffic control configuration messages.

When the DatapathController starts, it will open a channel to netlink and
maintain it until the DatapathController is destroyed. The DatapathController
already receives information about locally bound ports through the HostMapper,
and it receives the interface ids through the DefaultInterfaceScanner. From
the locally bound ports the QoS policy id can be retrieved, and from the
Virtual Topology manager the content of the QoS policy can be retrieved. From
all of this information, we can extract the most relavant trio of info: the
interface id, the rate, and the burst.

Whenever a new trio of information is received, the DatapathController will
do the following:

1. Remove any existing ingress qdisc that exists for this interface. This
   will clear out any previous configuration.
2. IF the triad has a rate and burst set, it will send a netlink request
   to the kernel to police this interface with the rate and burst.

#### Removing the existing configuration

The netlink request will have the equivalent effect of executing:

tc qdisc del dev $INTERFACE handle ffff: ingress

#### Adding a configuration

The netlink request will have the equivalent effect of executing:

tc qdisc add dev $INTERFACE handle ffff: ingress
tc filter add dev $INTERFACE parent ffff: protocol all prio 49
   basic police rate $RATE burst $BURST mtu 65535 drop

### DSCP Marking

There will be two changes in midolman to support DSCP marking:

1. QoSPolicyMapper - A new device mapper will be created for the virtual
   topology that will make the QoSPolicy objects available to the simulation.

2. When the simulation finishes, and it is determined that the packet must be
   encapsulated to be forwarded to a midolman peer host, then the QoSPolicy
   information will be queried via the QoSPolicyMapper and the tunnel TOS
   field will be set to this ports associated dscp_mark value.

[1] https://specs.openstack.org/openstack/neutron-specs/specs/liberty/qos-api-extension.html
