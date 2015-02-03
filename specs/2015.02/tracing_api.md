This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode

# Flow Tracing API

This document describes an API for enabling and disabling flow tracing
on a midonet cluster. When log tracing is enabled, all interactions of
the flow with the midonet agent are logged at the trace level.
Currently it is possible to enable these flow trace logs
agent-by-agent, using a JMX extension. The goal of this blueprint is
to allow administrators to enable tracing from a centralized
location. Note that this document does not cover the collection and
display of the flow trace log messages themselves. This is reserved
for future work. For now, log traces can be written to a local file on
the agent or sent to a remote ELK or Splunk instance.

## Motivation

The current method of enabling trace logging for flows is cumbersome and
labour intensive. The user has to know which hosts have ports attached
to the device they want to trace on, and then log into each host to
enable logging via JMX. In a multitenant environment this becomes
unfeasible, as a host may be shared among multiple tenants, and any
tenant enabling trace would be able to view potentially sensitive
information from other tenants.

The motivation for this change is to allow users to create trace
requests from one place and view all traces they currently have
running. A side effect of this is that tenants will be only able to
setup trace requests on their own devices, thereby bypassing the
security concerns from the previous solution.

## User facing changes

A new resource will be created in the REST API, under
/midonet-api/traces.

### Creating a trace request

POST /midonet-api/traces

Parameters:
{ "tenantId"   : "<tenant id>",
  "device"     : "<uri of bridge, router or port>",
  "condition"  : "<condition on which to trace for a given flow>",
  "maxFlowsPerHost" : "<max # of flows traced per host for this request>" }

Returns UUID identifying the trace request

### Viewing an existing trace request

GET /midonet-api/traces/:traceId

Returns
{ "tenantId"  : "<tenantId>",
  "device"    : "<uri of bridge, router or port>”,
  "condition" : "<condition>",
  "maxFlowsPerHost" : "<max # of trace logs per host>"}

### Deleting a trace request

DELETE /midonet-api/traces/:traceId

It is important to note that creating a trace request through the REST
API does not automatically enable it.

### Enabling a trace

PUT /midonet-api/traces/:traceId/enabled

### Disabling a trace

DELETE /midonet-api/traces/:traceId/enabled

### Checking whether a trace is enabled

GET /midonet-api/traces/:traceId/enabled

## Implementation details

Trace resources in the REST API map to trace znodes in
ZooKeeper. These are stored under /midonet/v1/traces.

When a trace request is created a UUID is generated, which is used to
refer to the trace request for its lifetime. The trace request znode
contains the trace condition, the trace device, the max trace logs per
host and a field to signify whether the trace request is enabled or
not.

When a trace request is enabled, a rule is inserted into the infilter
chain of the device for which the trace request was made, and the
enabled flag in the znode is set to true. When a trace request is
disabled, the trace rule is removed and the enabled flag is set to
false.

When simulating the path of a packet, the midonet agent applies all
the rules in the infilter chains of each virtual device which the
packet would traverse. If any of these chains contains a trace rule,
the context is tagged with the id of that trace rule, and the
simulation is restarted. The second time through, the context will
skip the trace rule as it is already tagged with the id.

Simulation only occurs at the ingress hosts of a flow. To allow trace
logs to be generated at the egress host of the flow, once we enable
tracing for a context on the ingress host, we send a FlowState message
to the set of interested hosts. The FlowState message contains a
FlowMatch, the id of the trace request and the id of the flow, which
is generated at the ingress host. Egress flows on the host are matched
against these FlowMatches to see if tracing should be enabled for the
flow.

Flow trace logs are output using slf4j, which in our codebase
ultimately outputs to logback. Context information is added to the
trace logs using MDC. This information contains the cookie id, the
flow id, the trace request id and the host id.

## Testing

Unit tests will be created for individually testing the


## Documentation

REST API documentation will be provided for trace objects to the same
level as the REST API for existing resource types.

## Work Plan

The task breakdown is as follows:

- REST API
  - Trace resource type
  - API documentation
- ZooKeeper changes
  - Trace object type
  - Trace enabling/disabling
- Ingress/Egress matching
- Adding context to trace logs

This work will be implemented by Ivan Kelly <ivan@midokura.com>.


