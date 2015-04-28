Cluster services guide for developers
=====================================

This document explains the design of the Cluster Services execution
framework, and provides information for developers desiring to write new
services that can run inside the MidoNet Cluster.

Introduction
============

The MidoNet Cluster was historically understood as the Storage backend
where MidoNet stores its network configurations and state information
(usually referred to as NSDB, Network State DataBase).  Additionally,
the MidoNet REST API was considered to be part of the cluster API,
offering a public interface to users and operators to manipulate said
configuration, as well as expose the internal state of the cloud.

Typically, operators allocate designated nodes for the MidoNet cluster
and use all of them to deploy ZooKeeper, Cassandra, and at least one
instance of the REST API.  Hypervisors and Gateway nodes would host the
MidoNet Agent, which interacts with ZooKeeper and Casssandra.

As part of the new MidoNet architecture (See [Cluster Design][1]) work,
the following goals was to enable a new type of Node that could
implement dedicated management functions.

In the context of the MidoNet 2.0 architecture, the MidoNet Cluster now
includes both the backend storages (ZooKeeper and Casandra), as well as
a number of Cluster services that we'll examine more in detail below.

In a typical deployment, we would have a pool of N Cluster nodes on
which we'd orchestrate and provision backend storages and management
services.

Existing Cluster Services
-------------------------

At the time of writing, services such as the VxLAN Gateway,
Configuration API, Topology API, provide good examples of use cases for
Cluster Services.  These services currently run as sub-services within
the web container used for the MidoNet REST API.

* VxLAN Gateway: this centralized, redundant management service
  orchestrates the synchronisation among physical VTEPs and virtual
  Bridges in order to implement L2 Gateways.
* Configuration API: offers an HTTP-based interface to manipulate the
  global configurations of all MidoNet components (same as mn-ctl, but
  via HTTP.)
* Topology API: this service provides an endpoint to access the new
  Storage API.  Clients can connect via
  TCP or WebSockets to query and subscribe to changes in the new
  topology storage. (This service is not yet in production.)

Implementing a Cluster Service
==============================

Design principles
-----------------

Cluster Services should be designed respecting the following principles.

- Single-Purpose.  Cluster Services should be designed to implement
  specific management functions.
- Autonomous.  A Cluster Service should consider itself as a
  self-managed distributed service.  It should not assume runtime
  dependencies on any other services (e.g., Service A shouldn't expect
  Service B to be running in the same JVM), nor make assumptions about
  their physical locations.
- Decoupled.  Services should define the public APIs through which other
  components are expected to interact with them. Note that public in
  this context doesn't mean user-facing (e.g., visible to operators and
  tenants), but visible internally to other services in
  the same deployment.

In order to enable developers to follow these principles, a number of
common libraries and tools will be provided.  A current example could be
the Storage API, the Cluster execution framework, or the Configuration
system itself that provides a powerful system for services to integrate
their own configuration needs into a common, user friendly configuration
framework.  Future tools will be implemented for Authentication, event
pub/sub BUS, etc.

Code Structure
--------------

All Cluster services are currently contained in the
cluster/midonet-cluster module, which may be confusing against the older
/cluster module.

Briefly: let's say you're implementing a new Cluster service that will
expose an RPC interface serving JSON, or Protobufs.  You should
implement the relevant POJOs or .proto files in /cluster and, if you're
providing a client-side library for the JVM, you should also place it in
here.  The server should be implemented in cluster/midonet-cluster.

More specifically:
- /cluster was historically meant to contain common "glue" used by
  multiple components and services inside MidoNet.  We're respecting
  this purpose.  You'll find here tools such as the ZOOM library, the
  MidoNet Backend module exposed via Guice to all components, or our
  library to access Configuration.  Additionally, client-side libraries
  and models will be included in /cluster.  An example is the Cassandra
  client, or the protobuf definition files defining low level MidoNet
  models, Neutron models, that any MidoNet component may use to interact
  with each other.
  - This module only generates a JAR file that is used as a dependency
    by all MidoNet components and services

- cluster/midonet-cluster depends on /cluster, and adds the actual
  management logic for Cluster services.  The code here contains the
  execution framework, as well as the implementation of our cluster
  services (such as VxLAN Gateway, Configuration REST API, etc.)
  - This module does NOT generate a JAR file.  Components such as the
    Agent will not (and should not) see any of the code contained in
    here.
  - This module generates a DEB/RPM package that is deployed in Cluster
    nodes.

Cluster execution framework
---------------------------

The Cluster execution framework is a simple application for the JVM
based on [Guice][2] that spawns a Daemon (org.midonet.cluster.Daemon) who
is in charge of bootstrapping the execution context, and spawning a
number of Cluster services ("Minions", which are implemented by
extending the ClusterMinion interface).

    abstract class ClusterMinion(nodeContext: Context) extends AbstractService

Note that a ClusterMinion is just an ordinary [Guice] AbstractService
that gets a Context object with information about the node within which
it's running:

    case class Context(nodeId: UUID, embed: Boolean = false)

(The embed property is a legacy flag that allows a service to know
whether it's running inside our new execution framework, or "parasiting"
the MidoNet REST API - the Daemon will set it as false always)

## Dependencies

An important point of departure with respect to MidoNet v1 is is a very
restricted use of Guice.  The bootstrapping logic and module
configuration evolved organically to a complex network of dependencies
and startup sequences (arguably unnecessary) that has proven unable to
scale for an architecture with more components and services.  We're
trying to take a much more strict approach here to prevent the same
problems.

There is an additional risk we want to avoid: that Services declare
runtime dependencies on each other.  For example:

    class ServiceA(context: Context) extends ClusterMinion(cfg) {
        @Inject
        var b: ServiceB

        def someMethod() {
            b.interact()
        }
    }

By doing this, ServiceA will immediately assume that its own JVM will be
running an instance of ServiceB.  This is incorrect, as we may chose
provision instances of services in other Cluster nodes.

Services should only rely on the dependencies that the Daemon choses to
expose, which are truly common to *all* MidoNet components.  Following
the code structure, this translates easily to stuff that lives in /nsdb
such as the MidoNet Backend service (that provides access to the backend
storage).  It essentially translates to the following (at the time of
writing):

    bind(classOf[MetricRegistry]).toInstance(metrics)
    bind(classOf[DataSource]).toInstance(dataSrc)
    bind(classOf[ClusterNode.Context]).toInstance(nodeContext)
    bind(classOf[ClusterConfig]).toInstance(clusterConf)

All Cluster services are allowed to share a MetricRegistry (that exposes
all service metrics on a single JMX endpoint per node).  The DataSource
is the legacy backend storage, as some services still rely on it. The
ClsuterNode.Context was just explained.  The ClusterConfig is the
instance of "cluster" configuration that was resolved from the
centralized configuration system.

## Lifecycle

For all practical purposes, the ClusterMinion behaves as a Guice
Service.  Implement the doStart and doStop methods accordingly in order
to initialize your service.

## Configuration

All Minions are free to define their own configuration parameters by
extending from MinionConfig.  These configs will be nested inside the
cluster schema (cluster.conf).  This is the configuration container for
the Configuration API:

    class ConfApiConfig(val conf: Config) extends MinionConfig[ConfMinion] {
        override def isEnabled = conf.getBoolean("cluster.conf_api.enabled")
        override def minionClass = conf.getString("cluster.conf_api.with")

        def httpPort = conf.getInt("cluster.conf_api.http_port")
    }

Note how we use the `conf_api` identifier for this service.  The next
two parameters are common to all Minions: "enabled" will be used to
determine whether the service is enabled or not in a given node.  The
minionClass determines what class will be executed (we'll expand about
this later.)

The third parameter is custom for this Minion (it defines the TCP port
on which the API will be exposed.)

After this class is ready, you should ensure that the cluster schema
is updated with the new configuration section.  In this case, we find
this in the cluster.conf file:

    conf_api {
        enabled : true
        enabled_description : "" ""

        with : "org.midonet.cluster.services.conf.ConfMinion"
        with_description : "" ""

        http_port: 8459
        http_port_description : "" ""
    }

## Registering the service

TODO


FAQ
===

Why not use OSGi, Karaf, -insert-framework-here- ?
-----------------------------------------------------

Being able to satisfy all our current use cases with one class, we were
reluctant to introducing a premature dependency on a complex framework.

The current approach doesn't exclude the possibility of moving to OSGi
or Karaf, or add any significant cost should decide to do it in the
future.  We simply didn't see the need yet.


[1]: <../specs/2015.02/cluster_design.md>
[2]: <https://github.com/google/guice>
