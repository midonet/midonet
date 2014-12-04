================================================================================
Network Function Insertion, Load Balancing and Chaining
================================================================================

There are many use-cases for transparently inserting network functions in the
path of network traffic. “Transparent” means both 1) that the traffic intended
to traverse the network function does not need to explicitly address (e.g. in
the IP destination field) the device that provides the function, and 2)that the
network graph (the L2 and L3 devices and wires in the overlay network) does not
need to be modified to redirect the traffic.

Example use-cases abound, but here are just a few NFs that may be inserted alone
or chained with other functions: caching proxies, reverse caching proxies, WAN
optimizers, L7 Firewalls, Intrusion Detection Systems, Intrusion Prevention
Systems.

“Insertion” describes the mechanism to interpose the NF is in the traffic’s
path. In practice, traffic that passes through a point in the network topology
and matches a classifier is “redirected” towards the NF. Traffic that isn’t
dropped by the NF should re-enter the topology either at the point where it was
redirected or, if the model allows, at an explicitly specified “reentry point”.
In most use cases, the corresponding return/reply traffic must also be
redirected to the NF, but may traverse its NICs in reverse order.

“Load Balancing” conveys the notion that a NF may be provided by a set of
instances. In general, any NFI (network function instance) in the set can treat
any of the traffic flows but a single flow’s packets and the corresponding
return/reply flow’s packets must traverse the same NFI. The set of instances is
referred to as a NFIP (Network Function Instance Pool) and should be dynamically
adjustable over time.

“Chaining” allows use-cases where traffic must be treated by multiple NFs or
where a single NF has been broken into components each of which implements part
of the NF. In some cases, the NFC (Network Function Chain) model is simply a
convenience: it allows redirecting once and then treating the traffic with a
sequence of NFs. In other cases, the initial classification stage has some
context that determines the required sequence of NFs, and reclassifying traffic
that has already traversed the first NF would not yield the same results.

In this document, the term “Network Function Chaining” is used as an umbrella
term that implies capabilities for 1) traffic redirection and reentry, 2)
instance pooling and load-balancing, and 3) function chaining.

Network Function Chaining in Neutron
================================================================================

Neutron does not have a set of APIs and models for Service Chaining. It’s worth
noting that both LBaaS (Load-Balancing-as-a-Service) and FWaaS
(Firewall-as-a-Service):
  * insert the function at a router; don’t have a flexible traffic
redirect/reentry API.
  * have no model for chaining
  * have no explicit model for scaling/pooling

The Juno cycle had three blueprints that addressed areas of Network Function
Chaining.

The Service Insertion Blueprint [1] was merged but the implementation was not
completed for Juno. It proposed a ServiceBase from which all Neutron Services
should inherit and a ServiceInterface that defines an insertion type and point.
Neutron Ports, External Ports and other Services are all valid insertion types
and points. In theory, our blueprint could leverage the ServiceInterface but we
believe insertion points should include an ordered list of classifiers plus
pointer to the Service or Network Function Chain that will treat traffic
matching the classifier.

The Traffic Steering Blueprint [2] was never merged. It proposed an API for
specifying a classifier and a list of ports to which matching traffic should be
steered. We find the proposal inadequate in two ways: it’s not compatible with
instance pooling and load-balancing; it does allow sharing a NF between two
Chains. Still, the blueprint’s Classifier object could be re-used in our
proposal.

The Service Chaining Blueprint [3] was never merged; however, the implementation
was committed to the Group Based Policy repository in Stackforge [4]. This API
provides:
  * a ServiceChainNode that has a HEAT template and configuration parameters
  * a ServiceChainSpec that is an ordered list of ServiceChainNodes
  * a ServiceChainInstance that represents one instance of a ServiceChainSpec
and specifies a port the chain is inserted.
We support the direction of this work, but we view it as a higher-level API that
should be layered above HEAT and Neutron. Note that any semantics for instance
pooling and load-balancing behavior are implicit in the HEAT templates. Also,
there is no way to specify how traffic should flow from Node to Node, and this
must therefore rely on convention.

Neutron is therefore lacking a low-level API that can be leveraged by other
layers (e.g. HEAT or GBP) to implement network function chaining in a flexible
way.

Proposed API and Resources
================================================================================

1. NetworkFunctionInstance (NFI)

Represents a single instance/device/appliance providing the network function.
The instance has two interfaces. Forward traffic is understood to arrive at the
instance via the left interface, and if the instance doesn’t drop it, leaves
the instance via the right interface. Conversely, return traffic reaches the
instance via the right interface and leaves the instance via the left interface.

The left and right interfaces of the instance are analogous to Neutron network
ports and must be treated by the SDN in a similar way. Nova must be able to bind
the to VM instance interfaces.

When the instance has only one NIC, the left and right interfaces are logical
only. In this case, the left-port and right-port will reference the same UUID
and the NFI instance will be extended to define a tag (VLAN or MPLS) that
should be injected in network packets to signal to the instance which logical
interface the packet is meant for.

Each instance defines administrative and operational status. Health monitoring
of instances is outside the scope of this proposal.

+--------------+-------+---------+----------+-------------+---------------+
|Attribute     |Type   |Access   |Default   |Validation/  |Description    |
|Name          |       |         |Value     |Conversion   |               |
+==============+=======+=========+==========+=============+===============+
|id            |string |RO, all  |generated |N/A          |identity       |
|              |(UUID) |         |          |             |               |
+--------------+-------+---------+----------+-------------+---------------+
|left-port     |string |RW, all  |''        |string       |left port of   |
|              |(UUID) |         |          |             |the network    |
|              |       |         |          |             |function       |
|              |       |         |          |             |instance       |
+--------------+-------+---------+----------+-------------+---------------+
|right-port    |string |RW, all  |''        |string       |right port of  |
|              |(UUID) |         |          |             |the network    |
|              |       |         |          |             |function       |
|              |       |         |          |             |instance       |
+--------------+-------+---------+----------+-------------+---------------+
|admin_status  |string |RW, all  |UP        |N/A          |Administrative |
|              |       |         |          |             |Status: UP or  |
|              |       |         |          |             |DOWN           |
+--------------+-------+---------+----------+-------------+---------------+
|op_status     |string |RW, all  |UP        |N/A          |Operational    |
|              |       |         |          |             |Status: UP or  |
|              |       |         |          |             |DOWN           | +--------------+-------+---------+----------+-------------+---------------+


2. NetworkFunctionPool (NFP)

Allows associating many interchangeable NFIs. The NFP is the mechanism that
provides pooling and load-balancing capabilities for an abstract network
function.

+--------------+-------+---------+----------+-------------+---------------+
|Attribute     |Type   |Access   |Default   |Validation/  |Description    |
|Name          |       |         |Value     |Conversion   |               |
+==============+=======+=========+==========+=============+===============+
|id            |string |RO, all  |generated |N/A          |identity       |
|              |(UUID) |         |          |             |               |
+--------------+-------+---------+----------+-------------+---------------+
|instances     |list of|RW, all  |          |N/A          |list of NFIs   |
|              |(UUID) |         |          |             |(by UUID) that |
|              |       |         |          |             |are in pool    |
+--------------+-------+---------+----------+-------------+---------------+


3. NetworkFunctionChain (NFC)

+--------------+-------+---------+----------+-------------+---------------+
|Attribute     |Type   |Access   |Default   |Validation/  |Description    |
|Name          |       |         |Value     |Conversion   |               |
+==============+=======+=========+==========+=============+===============+
|id            |string |RO, all  |generated |N/A          |identity       |
|              |(UUID) |         |          |             |               |
+--------------+-------+---------+----------+-------------+---------------+
|function-pools|list of|RW, all  |          |N/A          |ordered list   |
|              |(UUID) |         |          |             |of NFPs in the |
|              |       |         |          |             |chain          |
+--------------+-------+---------+----------+-------------+---------------+

4. InsertionPoint

+--------------+-------+---------+----------+-------------+---------------+
|Attribute     |Type   |Access   |Default   |Validation/  |Description    |
|Name          |       |         |Value     |Conversion   |               |
+==============+=======+=========+==========+=============+===============+
|id            |string |RO, all  |generated |N/A          |identity       |
|              |(UUID) |         |          |             |               |
+--------------+-------+---------+----------+-------------+---------------+
|insertion_type|enum   |RW, all  |          |N/A          |NEUTRON_PORT or|
|              |(UUID) |         |          |             |NEUTRON_ROUTER |
|              |       |         |          |             |               |
+--------------+-------+---------+----------+-------------+---------------+
|insertion_    |UUID   |RW, all  |          |N/A          |UUID of the    |
|point_id      |       |         |          |             |port or router |
|              |       |         |          |             |               |
+--------------+-------+---------+----------+-------------+---------------+
|services      |list of|RW, all  |          |N/A          |UUID of a      |
|              |(UUID,)|         |          |             |Classifier plus|
|              |UUID)  |         |          |             |UUID of a NFC  |
|              |pairs	 |         |          |             |or service     |
+--------------+-------+---------+----------+-------------+---------------+

5. Classifier (as defined in [2])

+------------+-------+---------+---------+--------------------+--------------+
|Attribute   |Type   |Access   |Default  |Validation/         |Description   |
|Name        |       |         |Value    |Conversion          |              |
+============+=======+=========+=========+====================+==============+
|id          |string |RO, all  |generated|N/A                 |identity      |
|            |(UUID) |         |         |                    |              |
+------------+-------+---------+---------+--------------------+--------------+
|name        |string |RW, all  |''       |string              |human-readable|
|            |       |         |         |                    |name          |
+------------+-------+---------+---------+--------------------+--------------+
|description |string |RW, all  |''       |string              |              |
|            |       |         |         |                    |              |
+------------+-------+---------+---------+--------------------+--------------+
|tenant_id   |string |RO, all  |from auth|N/A                 |              |
|            |(UUID) |         |token    |                    |              |
+------------+-------+---------+---------+--------------------+--------------+
|protocol    |int    |RW, all  |N/A      |0-255               |empty means   |
|            |       |         |         |                    |allow any     |
+------------+-------+---------+---------+--------------------+--------------+
|src_port_min|integer|RW, all  |         |1-65535             |              |
+------------+-------+---------+---------+--------------------+--------------+
|src_port_max|integer|RW, all  |         |1-65535             |              |
+------------+-------+---------+---------+--------------------+--------------+
|dst_port_min|integer|RW, all  |         |1-65535             |              |
+------------+-------+---------+---------+--------------------+--------------+
|dst_port_max|integer|RW, all  |         |1-65535             |              |
+------------+-------+---------+---------+--------------------+--------------+
|src_ip      |string |RW, all  |N/A      |IP address or subnet|              |
+------------+-------+---------+---------+------------+----------------------+
|dst_ip      |string |RW, all  |N/A      |IP address or subnet|              |
+------------+-------+---------+---------+--------------------+--------------+

References
==========

.. [1] ServiceBase and Service Insertion
   https://review.openstack.org/#/c/93128/22/specs/juno/service-base-and-insertion.rst

.. [2] Neutron Juno Traffic Steering Blueprint
   https://review.openstack.org/#/c/92477/7/specs/juno/traffic-steering.rst

.. [3] Neutron Juno HEAT-template based Service Chaining:
   https://review.openstack.org/#/c/93524/13/specs/juno/service-chaining.rst,unified
   http://specs.openstack.org/openstack/neutron-specs/specs/juno/service-chaining.html

.. [4] Group Based Policy in StackForge
   https://github.com/stackforge/group-based-policy

