================================================================================
Overlay Time Series Data Definition, Generation and Collection
================================================================================

MidoNet operators have use for a wide variety of overlay-related Time Series
Data. There are many examples, but consider measuring flow, packet and byte
counts of:
 - traffic ingressing/egressing a VM-facing Neutron port
 - traffic ingressing/egressing a virtual-router-facing port (measures
bandwidth to/from a Neutron network)
 - traffic from each Tenant Router to the Internet (measures each Tenant’s
consumption of uplink egress bandwidth)
 - traffic between VMs within a single Neutron network
 - dropped traffic to a Tenant, regardless of where it drops (e.g. no route, FW
at router, Security Groups). This can measure network attacks.
 - traffic from Endpoint Group A to Endpoint Group B (measures traffic between
two components of an application, or between two applications)

Our goal is therefore to easily enable both a rich set of default time-series as
well as custom time-series.

The solution should enable operators to succinctly define equivalent metrics for
a set of existing virtual topology objects as well as yet-to-be-created objects.
For example, if the operator wants to track per-Tenant usage of the Provider
Router’s uplink egress bandwidth, the operator should not have to act on a per-
Tenant basis, nor to reactively set up or tear down metrics as Tenants are
created or deleted.

The solution should allow the generated Time Series Data to easily be stored in
OpenStack monitoring/metering-as-a-service projects (Ceilometer and Monasca) as
well as popular infrastructure monitoring solutions (Nagios, Ganglia, Munin).

The generated Time Series Data should be rich enough to allow categorization and
further aggregation. Dimension annotations can be used to aggregate time series
to produce other interesting Time Series. For example, if the current time
period’s egress byte count for a VM-facing port is published like this:
*vmport.egress_bytes(port-id=10001, security-group=10002, security-group=10004, network-id=10005, tenant-id=10006, originating-tenant-id=10007)*
Then in addition to the per-VM bandwidth, we can easily derive:
- per-security-group bandwidth (note that the repetition of “security-group” in
the list of dimensions is intentional)
- per-network bandwith
- per-tenant bandwidth
- bandwidth to a specific VM from each Tenant it communicates with
MidoNet Agent should focus on producing *expressive/rich* time-series data and
not all possible time-series. External time-series can then be leveraged both to
combine cross-agent data-points for the same metric (i.e. when each Agent has
only a partial view) and to generate derived time-series.


Proposed API 
================================================================================

1. Metric

Each MidoNet metric track flow, packet and byte counts. A metric named
“port_egress” will generate counters for each of the following (if specified
in the object description):
- port_egress.flows
- port_egress.packets
- port_egress.bytes

A Metric further includes dimensions. For each unique set of dimension values
a separate bucket of counters is stored. For example, continuing with
“port_egress”, if the specified dimensions are [“host”, “tenant”] and there are
two hosts (“HostA” and “HostB”) and two teanants (“Tenant1” and “Tenant2”),
then the Metric will track flows, packets and bytes for each unique combination
of host+tenant for which traffic is seen. Assume that Tenant1 only has a VM on
HostA and Tenant2 only has a VM on HostB, then the Metric will produce counters
for each of the following:
- port_egress.flows(host=”HostA”, tenant=”Tenant1”)
- port_egress.packets(host=”HostA”, tenant=”Tenant1”)
- port_egress.bytes(host=”HostA”, tenant=”Tenant1”)
- port_egress.flows(host=”HostB”, tenant=”Tenant2”)
- port_egress.packets(host=”HostB”, tenant=”Tenant2”)
- port_egress.bytes(host=”HostB”, tenant=”Tenant2”)

+--------------+-------+---------+----------+-----------------+-----------------------+
|Attribute     |Type   |Access   |Default   |Validation/      |Description            |
|Name          |       |         |Value     |Conversion       |                       |
+==============+=======+=========+==========+=================+=======================+
|id            |string |RO, all  |generated |N/A              |identity               |
|              |(UUID) |         |          |                 |                       |
+--------------+-------+---------+----------+-----------------+-----------------------+
|name          |string |RW, all  |''        |only a-z/        |name of the            |
|              |       |         |          |0-9/./_/-        |metric                 |
|              |       |         |          |                 |                       |
+--------------+-------+---------+----------+-----------------+-----------------------+
|dimensions    |list of|RW, all  |''        |”src-host”,      |Different              |
|              |string |         |          |”src-sec-group”, |counters/buckets       |
|              |(enum) |         |          |”dst-sec-group”, |will be kept for       |
|              |       |         |          |”src-tenant”,    |each unique combination|
|              |       |         |          |”dst-tenant”,    |of dimension values    |
|              |       |         |          |”orig-ingr-port”,|                       |
|              |       |         |          |”dev-ingr-port”, |                       |
|              |       |         |          |”dev-egr-port”,  |                       |
|              |       |         |          |”ip protocol”    |                       |
+--------------+-------+---------+----------+-----------------+-----------------------+
|counters      |list of|RW, all  |          |”flows”,         |e.g. [flows, packets]  |
|              |string |         |          |”packets”,       |means only flows and   |
|              |(enum) |         |          |”bytes”          |packets, not bytes will|
|              |       |         |          |                 |be counted             |
+--------------+-------+---------+----------+-----------------+-----------------------+

2. MetricAttachment

MetricAttachments solve the problem automatically attaching Metrics to virtual ports,
bridge, routers. They provide a form of templatization so that they can be attached
to devices created in the future.

For example, assume we want to track inter-port traffic on every Router. We create a
Metric with name=”router_intra_subnet” and dimensions=[“dev-ingr-port”,
“dev-egr-port”]. Then you would create a MetricAttachment that is templatized on type
Router (i.e. attachment_template=”router:ALL”). MidoNet will automatically associate
the Metric with every Router and the Router-ID is automatically added to the dimension
list.

However, if you want to track the inter-port traffic on just two specific routers, then
you would create two MetricAttachments, each referencing the same Metric, but each with
one of the Router IDs specified.

For another example, assume we want to track hit-counts on a Chain1’s rules and Chain1
has ID=01234. Create a Metric with name=”rule_hit_count” and counters=[“flows”,
“packets”]. Then create a MetricAttachment that is templatized on type Rule associated
with Chain1 (i.e. attachment_template=”rule:01234”). MidoNet will automatically
associate the Metric with every Rule in Chain1 and Rule-ID ia automatically added to
the dimension list.

+--------------+-------+---------+----------+-----------------+-----------------------+
|Attribute     |Type   |Access   |Default   |Validation/      |Description            |
|Name          |       |         |Value     |Conversion       |                       |
+==============+=======+=========+==========+=================+=======================+
|id            |string |RO, all  |generated |N/A              |identity               |
|              |(UUID) |         |          |                 |                       |
+--------------+-------+---------+----------+-----------------+-----------------------+
|name          |string |RW, all  |''        |only a-z/        |name of this           |
|              |       |         |          |0-9/./_/-        |attachment spec        |
|              |       |         |          |                 |                       |
+--------------+-------+---------+----------+-----------------+-----------------------+
|metric        |(UUID) |RW, all  |''        |string           |name of the referenced |
|              |       |         |          |                 |metric                 |
|              |       |         |          |                 |                       |
+--------------+-------+---------+----------+-----------------+-----------------------+
|attachment_   |(UUID) |RW, all  |''        |string           |ID of a specific port, |
|point         |       |         |          |                 |bridge, or router      |
|              |       |         |          |                 |where the metric should|
|              |       |         |          |                 |be tracked             |
+--------------+-------+---------+----------+-----------------+-----------------------+
|attachment_   |string |RW, all  |''        |”<type>:<param>” |The metric will be     |
|template      |       |         |          |where type is one|tracked at any device  |
|              |       |         |          |of ”router”,     |of the specified type  |
|              |       |         |          |”bridge”, “rule”,|that is associated with|
|              |       |         |          |or ”port”        |the tenant/bridge/     |
|              |       |         |          |and param is the |router/chain specified |
|              |       |         |          |ID of a tenant,  |in the parameter. If   |
|              |       |         |          |router, bridge   |param is “ALL” metric  |
|              |       |         |          |or chain.        |is tracked for all     |
|              |       |         |          |                 |devices of that type   |
+--------------+-------+---------+----------+-----------------+-----------------------+
|filter        |(UUID) |RW, all  |''        |string           |ID of a filter. The    |
|              |       |         |          |                 |metric will only be    |
|              |       |         |          |                 |tracked for traffic    |
|              |       |         |          |                 |that passes the filter |
+--------------+-------+---------+----------+-----------------+-----------------------+

3. Filter

TO BE DECIDED - can we define the filter as FWaaS rules or the Classifier
defined in the Traffic Steering [1] blueprint?


Additional Configuration
================================================================================

MN’s Neutron Vendor Extensions will come with a pre-defined json/xml file
containing the Metric and MetricAttachment definitions for a set of Time Series
that should be tracked by default. These should include:
 - per tenant use of Provider Router uplink egress bandwidth
 - per Neutron network port traffic (includes virtual router’s network port)
  -- separate Metrics for ingress/egress

Operators/deployers can extend this list at any time. On restart, MN’s Neutron
plugin verifies that all the definitions are installed in Neutron DB. However,
deleted definitions are not removed from Neutron DB, they must be removed via
CLI or GUI.

Metrics Aggregation, Transformation, and Querying
================================================================================

MidoNet will not have a built-in mechanism for storing, transforming, querying,
alarming, notifying about the generated Time Series Data.

MN Agent will simply publish via JMX the counters produced by each Metric
activated/traversed by local flows.

MN/Neutron configuration (file and/or API-driven) will specify the backend
storage type: Nagios, Ganglia, Ceilometer/Gnocchi, Monasca.

MN Agent will be packaged with a (MidoNet project-specific) Nagios plugin that understands the MN Agent’s name format for JMX metrics. The plugin will
periodically (based on configuration) poll all the JMX metrics and transform
them to Nagios.

The Nagios plugin can directly or indirectly used by the collectors of all of
the monitoring systems to push the time-series points to their backend storage.

The operator can then use the specified monitoring-system to view graphs, define
alarms, define notification mechanisms, or for more advanced functionality like
anomaly detection.


Implementation Notes
================================================================================ 

Metrics and Metric Attachments must be translated to lower-level API. Metric
dimensions may be understood natively by MN Agent or implemented at the Cluster
or Neutron plugin layer. Similarly, MN Agent may understand Metric Attachments
natively or a higher layer may watch for device creation/deletion and make
corresponding modifications in MidoNet.

MN Agent publishes via JMX a set of counters whose names are composed from the
Metric name and the unique set of dimensions/values. For example:
 - port_egress.flows/host=HostA/tenant=Tenant1
TODO: check the valid characters for JMX counter names.

Finally a note on the file with default metric and attachment definitions. This
template-based and default-definitions-based approach allows the MN Agent to
avoid default behavior (e.g. knowing to track traffic on every virtual router
port).


References
================================================================================

.. [1] Neutron Juno Traffic Steering Blueprint
   https://review.openstack.org/#/c/92477/7/specs/juno/traffic-steering.rst

