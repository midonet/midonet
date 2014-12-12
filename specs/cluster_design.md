# MidoNet 2.0 Cluster Design

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

### Model Translator

The Translator is able to convert all high-level entities from Neutron's model
into the corresponding low-level entities in MidoNet's model, and persist them
in MidoNet's backend storage.

### Neutron importer and synchronizer

The Synchronizer will be able to continuously read individual updates from the
NeutronDB producing the corresponding CRUD operations on the low-level models.
This process maintains both NeutronDB and MidoNet's storage in sync.

The Translator is the only component that may write to MN Storage, with the
following exceptions: device control plane logic writes to mac, arp, and
forwarding tables; supervisor logic may write virtual port and physical host
status, connectivity among hypervisor hosts.

The Importer is a Neutron-side component that reads the full content of
NeutronDB and generates a set of CREATE operations into the update log,
plus a special update type FLUSH.  The synchronizer detects this
message, sets the MidoNet cluster in "maintenance" mode, and regenerates
the entire MN database from scratch reading and translating all the
CREATE operations in NeutronDB into low-level models that are stored in
MN's backend storage.  This storage may be anything from on-disc,
in-memory, in-process, or distinct modularized components.

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

The main goal of this phase is to convert Neutron’s API Server (plus our
vendor extensions) in MidoNet’s only writable API for cloud and custom
integrations.  Neutron’s API Server and Neutron DB become therefore core
components of MidoNet.

This implies the following tasks:

#### Replace MN 1.x REST API with Neutron Importer and Synchronizer

Our current REST API will no longer be used by MN's Neutron plugin to
write low-level models into MN's storage.  This requires implementing
vendor extensions to support Provider Router, BGP, VXLAN L2 Gateway,
VLAN L2 Gateway, Host-interface-port bindings, and other functionality
that was previously only provided by MN’s internal/proprietary API.

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
  ZOOM.  As part of Phase 2, the ZOOM-based implementation will be
  replaced by one based on an RPC that will interact with a distributed
  Cluster for access to topology and subscriptions.

- This refactor only affects Topology data (e.g., virtual network
  devices such as Bridge, Port, Chain), but not state (e.g., mac-port
  tables, arp tables, routing tables).  State is maintained using
  replicated data structures based on Zookeeper that can remain
  operative as long as the Agent keeps talking directly to Zookeeper.
  State will be implemented by the Cluster as part of Phase 2.

#### Topology API

The Topology API (`org.midonet.brain.services.topology`) will provide an
RPC mechanism that can be used to access the low-level models.  The
Topology API exposes a simple API defined using Protobuf messages in
`cluster/src/main/proto/topology_api.proto`.  A client may use these
messages to Get or Subscribe to different entities in the topology.
There will be two interfaces to access this API implemented with Netty
adapters: one with WebSockets to be used already in Phase 1 by the
MidoNet Manager; a second TCP interface will remain in experimental mode
and eventually used by the Agent to gain access to configuration and
updates.

### Phase 2

NOTE: this section is explained superficially and only tries to give a
high level view of the roadmap, it will be expanded in the future with
more detailed information.

After Phase 1 is implemented MidoNet will be able to import the content
of Neutron DB into its own Storage, synchronize updates as they are made
via the Neutron API, as well as offer a read only view of the low-level
models for internal components such as a the MidoNet Manager and Agent.
The former will be accessing the Storage through the MidoNet Cluster
using the WebSockets interface. The Agent will still be accessing
Zookeeper directly through the new ZOOM-based libraries. State (mac-port
tables, etc.) are still based in the 1.x implementation (Replicated
Maps with Zookeeper as backend).

The goal of Phase 2 will be to remove all direct storage access from the
Agent to the storage. For this purpose, the ZOOM based implementation of
the storage libraries used by the agent will be replace by a RPC
implementation that will instead keep a connection to a cluster node.
The Cluster node will attend requests and subscriptions for topology
elements and stream all relevant updates to the Agente The server side
component will in fact use ZOOM to connect to the Zookeeper ensemble and
serve the data to the Agents connected to it.  In practise, it will be
observed that the work mostly involves moving the ZOOM libraries from
the Agent to the Cluster, and tending the RPC interface between Cluster
and Agent.

A second part of the work will be to provide an alternative for
Ephemeral State storage (arp tables, routes, etc.).

From this point, further work on optimizing storage will be enabled,
such as deploying Zookeeper Observer nodes in Cluster nodes. This work
will be left out for future specs.
