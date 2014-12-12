This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode

# New topology propagation pipeline

This document explains the design of the MidoNet cluster, a distributed
component that abstracts the storage and propagation of virtual network
topology and device state such that the Northbound API can be "rendered"
by the MidoNet Agents.

NOTE: Paths in this document are relative to the root path of the
https://github.com/midonet/midonet repository

## Goals

1. Solve the problem of MidoNet's backend storage coming out of sync
   with Neutron DB.  Provide instead a reliable event processing
   pipeline to sync MN's store taking NeutronDB contents as the
   authoritative source for configuration.

2. Reduce operation burden and update complexity. Instead of data
   migration scripts, MidoNet should be able to automatically reimport
   the contents of NeutronDB.

3. Improve development agiligy by reducing the amount of boilerplate
   code required for new feature development.  Express low-level models
   using an IDL that is easy to modidy, extend, and share accross
   different MN components.  Support mapping IDL models directly to/from
   storage backends.

## MN State propagation pipelines and components

This section explains how state changes propagate from Neutron to Agents,
between Agents, and from Agents to Neutron.

### MidoNet synchronizer

This component is able to read a log of CRUD operations from Neutron DB
affecting a set of high-level entities from the Neutron model, translate
them into operations on the low-level MidoNet model, and persist them in
MidoNet's backend storage.  This storage may be anything from on-disc,
in-memory, in-process, or distinct modularized components.

The Synchronizer is thus the only one that may write to MN Storage, with
the following exceptions: device control plane logic writes to mac, arp,
and forwarding tables; supervisor logic may write virtual port and
physical host status, connectivity among hypervisor hosts.

Full imports of Neutron DB become just a subcase of the synchronization
by virtue of the FLUSH operation, which will be described in the
following section.

### Neutron importer

The Importer is a Neutron-side component that reads the full content of
NeutronDB and generates a set of CREATE operations into the update log,
plus a special update type FLUSH.  The synchronizer is able to detect
this message, and reacts by setting the MidoNet cluster in "maintenance"
mode, and processing all the CREATE entries which in practise
regenerates the complete MidoNet storage.

The Importer only runs when MidoNet is first installed (e.g., in a new
or existing Neutron deployment) or when the MN backend data needs to be
recovered (e.g., after taking it out of service or after upgrades that
modify the backend data schemas).

Google's Protobufs was chosen to represent both the Neutron high level
model as well as the MidoNet low level model based on the following
reasons:
- Code generation for major languages (C++, Java, Python)
- Support for both binary and json encodings.
- Efficient encoding and decoding.
- Backwards compatible decoding of different versions of the schema.

### MN State Propagation Cluster

After Neutron high-level models have been imported/synced into low-level
models and stored in MidoNet's backend storage, the Cluster needs to
make this data available to:
- Agents, who need to retrieve low-level models for configuration and
  state, as well as subscribe to changes.
- MidoNet Manager and internal debugging tools allowed to examine MN
  low-level models (read-only).

## Implementation

The main principles behind this approach are to maintain a single line
of development in MN, making all necessary changes directly in trunk and
ensuring backwards compatibility at all times.

Cluster elements meant for use by other MidoNet components such as
client libraries or APIs are developed under /cluster.  Internal
services to the cluster live under /brain/midonet-brain.

### Phase 1

The main goal of this phase is to convert Neutronâs API Server (plus our
vendor extensions) in MidoNetâs only writable API for cloud and custom
integrations.  Neutronâs API Server and Neutron DB become therefore core
components of MidoNet.

This implies the following tasks:

#### Replace MN 1.x REST API with Neutron Importer and Synchronizer

Our current REST API will no longer be used by MN's Neutron plugin to
write low-level models into MN's storage.  This requires implementing
vendor extensions to support Provider Router, BGP, VXLAN L2 Gateway,
VLAN L2 Gateway, Host-interface-port bindings, and other functionality
that was previously only provided by MNâs internal/proprietary API.

For each API operation in Neutron, the plugin adds an entry in the
`midonet_tasks` table which is created with the alembic migration tool
used in Neutron.  It follows the Neutron model for ORM and migrations.

To accomplish this, for every API call that modifies data, inside the
single transaction block that updates all the other Neutron tables, call
the `create_task` method.  The task data access module exposes the
following method

    create_task(context, task_type_id, data_type_id, resource_id, data)

Creating a new task entry of type, `task_type_id`, for the resource with
`resource_id`, and data type, `data_type_id`. The actual data stored in
data arg.  The request entry is to be added to `midonet_tasks` table in
the same DB session as the one contained in context  `data` is a
dictionary object.  This method does not return anything.  An exception
is raised on failure.

The data contained in each `task` entry will be modelled using an IDL
expressing the full Neutron model, plus MidoNet-specific Vendor
Extensions. This enables sharing the same model in different components
written in different languages (Python on the Neutron side, Java/Scala
on the Cluster side).  The Protobuf models will be encoded using JSON in
order to facilitate debugging by directly querying NeutronDB.

Neutron Protobuf models can be examined at
cluster/src/main/proto/neutron.proto

As a result of this effort, MidoNet will offer an event processing
pipeline able to read updates from Neutron DB and make the corresponding
mutations in MidoNet's storage.

#### Implement model translation and storage

For its translation abilities, this component is being developed under
codename C3PO after the Star Wars character.  It's being developed under
`org.midonet.brain.services.c3po`.

The low-level model definitions for MidoNet can be found at
`cluster/src/main/proto/topology.proto`, forming MidoNet's low-level
domain.

This storage will remain based on Zookeeper since it provides strong
consistency guarantees, as well as ordered notifications on data updates
which are fundamental for the State Propagation Cluster.  We will
however store the encoded Protobuf messages directly in the storage, and
provide an ORM-like tool, Zookeeper Object Mapper (ZOOM) able to perform
typed CRUD operations on any plain Java object (POJO) and thus
Protobufs.  ZOOM also provides support for subscriptions on individual
objects, as well as collections of objects of a given type.  ZOOM
resolves most of the boilerplate problem in 1.x: new models can be
defined in the IDL, and ZOOM will be immediately able to serialize them
in storage.  The Storage API offered by ZOOM also abstracts the storage
choice, allowing future implementations using other backends
transparently to the rest of the system.

#### Agent refactor

In MN 1.x, Agents access Zookeeper directly using the DataClient
interface and a set of auxiliary classes (Device Managers) that
configure watchers on Zookeeper paths where each device of interest is
stored.

As a consequence of the changes above, the old storage layer will no
longer be valid to access the low-level models since the old REST API
will no longer be writing the data.

Agents will thus require a refactor in order to adapt the distributed
Topology management components to the new storage.  This affects the
Device Managers, who employ the DataClient to retrieve low-level models
and build the simulation objects that are then handed over to the
VirtualTopologyActor (VTA) and VirtualToPhysicalMapper (VTPM), who cache
and expose the simulation objects to various parts of the simulation
code.

In order to keep the 1.x layer functional during the refactor,
development is focusing on creating a new storage stack based on ZOOM
that can compose compatible simulation objects and feed them to the VTA
and VTPM transparently.

As a result of this job, the MidoNet Agent will be able to perform
simulations both with the old and new storage layer, provided that the
backend (Zookeeper) has been populated accordingly.

There are two important considerations to this work:

- This phase does not involve decoupling the Agent from the storage
  choice itself (Zookeeper).  It does however demarcate a clear boundary
  between agent and cluster at the Storage interface implemented by
  ZOOM.  As part of later phases, the ZOOM-based implementation may be
  replaced by one based on an RPC that will interact with a distributed
  Cluster for access to topology and subscriptions.  In practise, this
  will involve moving most of the ZOOM-based implementation to the
  cluster nodes, behind the RPC server-side interface serving the
  Topology API.
- This refactor only affects Topology data (e.g., virtual network
  devices such as Bridge, Port, Chain), but not state (e.g., mac-port
  tables, arp tables, routing tables).  State is maintained using
  replicated data structures based on Zookeeper that can remain
  operative as long as the Agent keeps talking directly to Zookeeper.
  State will be implemented by the Cluster as 

#### Topology API

The Topology API (`org.midonet.brain.services.topology`) will provide an
RPC mechanism that can be used to access the low-level models.  The
Topology API exposes a simple API defined using Protobuf messages in
`cluster/src/main/proto/topology_api.proto`.  A client may use these
messages to Get or Subscribe to different entities in the topology.
There will be two interfaces to access this API implemented with Netty
adapters: one with WebSockets to be used by the MidoNet Manager; a
second TCP interface will remain in experimental mode and may be
eventually used by the Agent or other components (e.g., debug tools) to
gain access to configuration and updates.
