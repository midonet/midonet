# QOS support

Quality of Service support means 2 things:

1) Shaping: giving different priority to different traffic types
2) Policing: limiting the amount of traffic on an interface

This document goes over the design and high level implementation details
for achieving ingress policing on bound ports. Egress policing and shaping
are not considered.

## Overview

The goal of this feature is to provide a way for a user to configure
ingress policing on bound ports. A bound port is a virtual port that
corresponds to a physical interface. The policing configuration is 
composed of 2 values:

rate - the maximum bandwidth
burst - the maximum amount of bytes that can be received instantaneously

This is what the work flow will look like:

1. The user will configure a port with a rate and a burst using neutrons
   QoS extension.
2. Neutron will then send this information over to the midonet-cluster,
   where the midonet-cluster translates this information into a format
   that the agent can use. This format is just new rate and burst fields
   on the Port object.
3. The agent will listen for updates regarding locally bound ports with
   rate and burst information, and send a request to the linux kernel to
   configure the physical interface with the appropriate traffic control
   constructs.
4. The linux kernel will police traffic.

The code changes are broken down into 3 main areas: Neutron Plugin,
Midonet Cluster, and Midolman.

## Neutron Plugin

The Neutron plugin changes will focus on port creation and port update.
The Neutron QoS model involves policy and rule objects, but they are only
relevant to midonet when they are associated with a port.

### Port Create

When a port is created,
    if the port has qos policy associated with it, query for the rate and
        burst of that policy and assign it to the port.
    else if the network the port is on has a qos policy associated with it
        assign the rate and burst of that policy to the port.
    else just create the port as normal.

### Port Update
    if the port has qos policy associated with it, query for the rate and
        burst of that policy and assign it to the port.

    update the port as normal.

#### Note
  This plugin implementation is a departure from what we have done for
  previous features. In the past, we have always added as little logic
  to the plugin as we could get away with, pushing all translation logic
  to the midonet cluster. The reasoning for that was that the model in
  neutron usually roughly corresponded to the model used by the simulation,
  with some extra work needed by the translation for accounting and some
  slight additions for the midonet model.
  
  However, in this case, the model is more complex than the information
  we need to implement policing. Further, the information we need is not
  actually used by the simulation. We only need the rate and burst per
  port information so that we can program the linux kernel. 

## Midonet Cluster

The Midonet Cluster changes will be very small. They will be:

1. Add a rate and burst to the NeutronPort and the Port objects.
2. In the PortTranslator, update the corresponding Port object
   with the rate and burst info for both translateUpdate and
   translateCreate.

## Midolman

The Midolman changes will not have any simulation changes, but will add a
service that listens for qos information related to locally bound ports.
Once this service has the information it needs, it will program the linux
kernel with the appropriate traffic control constructs.

The MidolmanModule will create an instance of QoSService. This service will
subscribe to the HostMapper and DefaultInterfaceScanner observables and
consolidate this information to a triad (interface id, rate, burst).
whenever a new triad is received, the QoSService will do the following:

1. Remove any existing configuration that exists for this interface. This
   will clear out any previous configuration.
2. IF the triad has a rate and burst set, it will send a netlink request
   to the kernel to police this interface with the rate and burst.

The QoSService will keep an open netlink connection to send configuration
requests.

### Removing the existing configuration

The netlink request will have the equivalent effect of executing:

tc qdisc del dev $INTERFACE handle ffff: ingress

### Adding a configuration

The netlink request will have the equivalent effect of executing:

tc qdisc add dev $INTERFACE handle ffff: ingress
tc filter add dev $INTERFACE parent ffff: protocol all prio 49
   basic police rate $RATE burst $BURST mtu 65535 drop
