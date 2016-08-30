# QOS support

The initial support for QoS features in midonet will match what is
available for the Neutron Newton release. This is support for options:

1. Modify the DSCP field of IP packets ingressing from a given port.
2. Police ingress bandwidth for a given port.

These two features correspond to the Newton QOS rules described in [1].

## Overview

The implementation details of the two features (policing and dscp marking)
will be roughly the same for the neutron plugin and the translators. However,
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

Ingress policing configuration from the user involved two values:

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
   constructs.
4. The linux kernel will police traffic.


The code changes for both features are broken down into 3 main areas:
Neutron Plugin, Midonet Cluster, and Midolman.

## Neutron Plugin

The neutron plugin will simply pass along the information configured by
the user to the midonet cluster. However, the plugin will do some extra
work by ensuring that all of the relevant QoS data is sent over in a
consolidated single rest call. The various types of rules will be nested
in the QoS policy object.

qos_policy {
    id: UUID
    bandwidth_limit_rules: List of bw_limit
    dscp_marking_rules: List of dscp_mark
}

dscp_mark {
    dscp_mark: INT
}

bw_limit {
    max_kbps: INT
    max_burst_kbps: INT
}

It may seem useless to have a list of the dscp_mark rules as it is a single
value. However, this rule may be expanded in the future to specify things
like tcp port or ip.

## Midonet Cluster

The Midonet Cluster will expose one new API endpoint. This endpoint will
accept a POST, PUT, and DELETE requests. The POST and PUT handles will
accept an entire qos policy object as its content, and forward it to the
QOS policy translator. The DELETE request will accept a UUID.

The Port midonet model will have a back-reference to the qos policy id
added to it.

An additional QoSPolicy midonet object will be defined that looks similar
to the neutron model. We need both because the neutron model object will
mirror the neutron data, but the midonet model will be accessible by the
agent.

The QOS Translator will create, update, or delete the equivalent midonet
model object and store it in ZooKeeper, making it available to midolman.

## Midolman

### Ingress Policing

The Midolman changes for ingress policing will not have any simulation
changes, but will add a new QoSService that listens for qos information
related to locally bound ports by watching for changes made to the Port
objects QoSPolicy objects.

Once this service has the information it needs, it will program the linux
kernel with the appropriate traffic control constructs.

The MidolmanModule will create an instance of a QoSService. This service will
subscribe to the HostMapper, DefaultInterfaceScanner, and QoSPolicyMapper
observables and consolidate this information to a triad (iface id, rate, burst).

whenever a new triad is received, the QoSService will do the following:

1. Remove any existing ingress qdisc that exists for this interface. This
   will clear out any previous configuration.
2. IF the triad has a rate and burst set, it will send a netlink request
   to the kernel to police this interface with the rate and burst.

The QoSService will keep an open netlink connection to send configuration
requests.

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
