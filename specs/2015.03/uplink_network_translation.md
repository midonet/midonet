..
This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode


# Uplink Network Translation

In MidoNet 2.0, the Neutron integration allows operators to configure the
uplink networks.  This document describes the translations needed for all the
uplink network management Neutron APIs.


## Port

Ports created on the uplink have binding details:

 * 'binding:host_id': ID of the host to bind the port on
 * 'binding:profile': Dictionary containing 'interface' key indicating the name
                      of the interace to bind the port to

There is no extra translation required.


## Router Interface

When port is supplied in the router interface add API and this port has the
binding information associated, add the port-host-interface mapping so that the
binding takes place.

In the router interface delete API, if the port has binding details attached,
unbind the port and delete the port.
