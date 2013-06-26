# Overview

This document describes the MidoNet upgrade policy and process.


# MidoNet Versioning

MidoNet is versioned in <i>x.y.z</i> format.

- x is the major version.  When this number is bumped up, it signifies
there were new features that require either major API or data schema changes.
For the API changes, it is likely that the clients would have to
re-read the API specifications and modify their applications
significantly to be compatible.  For data changes, it means that the
upgrade process will require major data migration.  It is hoped
that the major version is backward compatible with the previous
major version, but it is also possible that it is not, and that the
code is designed to handled the mixed versions running during the
upgrade process.  When there are backward incompatbile changes,
then it is expected that all the midonet agents and API are upgraded
to this version before they can be upgraded the subsequent versions.

- y is the minor version.  When this number is bumped up, it indicates
new features and bug fixes that may or may not include API changes,
but they are backward compatible with previous minor versions within
the major version.

- z is the patch version.  When this number is bumped up, it means
there were bug fixes, and no new feature went in.  API and data
schema do not change either.  Backward compatibility would not be
broken.


# API Versioning

MidoNet API is versioned globally and individually per media type.
Any change in the media type bumps up the media type version.

For example, assuming the original Router version is 1, it is
represented in media type as:

<i>application/vnd.org.midonet.Router-v1+json</i>

If the Router DTO class changes, we bump the version:

<i>application/vnd.org.midonet.Router-v2+json</i>

The global API version indicates the set of all the media types
supported, including their versions.  The global API version has a
format of <i>x.y</i>:

- x is the major version.  It is bumped up when there are big
changes in the API that requires the clients to re-structure their
code.

- y is the minor version  It is bumped up when there are any
changes to the API that are not considered to be major.

The global API version can be discovered by doing a GET request
on the root URI.  The media type versions are described in the
[REST API specification](rest-api-specification.md).


# Data Versioning

The data is versioned in the format <i>x.y</i>, and it is global for
the entire ZooKeeper data:

- x is the major version.  It is bumped up when there are big
changes in the data that may introduce backward incompatible
changes or requires data migration to run.

- y is the minor version.  It is bumped up when there are any
changes the data that do not require data migration.  It is
recommended that for minor upgrades, only new fields are added
to the DTOs so that it does not break the deserialization.

NOTE: Although adding fields is backward compatible because deserialization
is capable of simply ignoring fields it does not recognize, new fields still
have to be handled as 0/null in new features when the system is still
deserializing DTOs from older formats. Furthermore, API servers will be
disabled during the upgrade process, which means that later version features
can not be configured until all nodes are upgraded.

The version is stored in the data of all the nodes.  It is
stored in the outer JSON object:

{"version": "x.y", "data": { &lt;JSON data&gt; }}

This version is there so that the code can use this value to
determine which DTO to deserialize to.

Cassandra data is not versioned. For per-flow data, it would
simply expire and be replaced by the new data format, and the
code has to be able to handle both formats for backward
compatiblity.  For stats, the newly added data after the
schema change would be taken into account when the data is
aggregated.


# Upgrade Process

In ZooKeeper, we keep the following global versioning information:

- '/write_version': The data version in which the midolman agents can
write to ZooKeeper.  This is useful when going through the upgrade
process and you want to make sure that Midolman agents do not start
writing data in the new version until all the nodes can read the
new data.

- '/versions/&lt;x.y&gt;': This node has a child for each version, and
Midolman agents register themselves to the version that they are
running.  This is useful for the data migration coordinator to
determine when all the agents have been upgraded.

The following information may be added in the future to further
assist the upgrade process:

- '/state': This node holds the state of MidoNet.  The only states
that are being considered currently are 'Running' and 'Upgrading'.

- '/migration_version': The version for which the last time data
migration tool ran.  This is useful for the data migration tool to
determine which version the current ZooKeeper data is in.  It is
not necessary until the first data migration occurs.

The high level recommended upgrade process is described below.
It is assumed that there is an upgrade coordinator service running
that coordinates the process.

1. Disable MidoNet API servers.
2. Dump Zookeeper data in case we need to rollback.
3. Start upgrading Midolman agents.  They will register themselves
in the new '/versions/&lt;x.y&gt;' directory.  The new Midolman, though
it can read and write the new version data, it does not do so because
'/write_version' is still set to the previous version.  These Midolman
agents can still read and write the data in the previous versions
that they are compatible with. Dump of ZK could be done at this point
too as it would contain the newer data.
4. The coordinator updates '/write_version' to the newest version once
it verifies that all the agents are upgraded to the new version
(by looking at the '/versions/&lt;x.y&gt;' directories).  This triggers
notification to all the Midolman agents to start writing the new data
format.  At this point, no old data format should be written.
5. If necessary, run the migration tool to transform the left-over
ZooKeeper data in the old format to the new format.  When it finishes,
it updates '/migration_version' to match '/write_version'.
6. Upgrade and start MidoNet API servers.  At this point, all the
MidoNet agents are upgraded, and the Zookeeper data is in the newest format.

The only way to rollback at this moment is to restore the dumped
ZooKeeper data and manually rollback the versions of the agents and
the API, which could mean there there would be service interruption.


# Deprecation

Since major version releases could contain backward incompatible
changes, the non-supported code could be deprecated and removed in
these releases.
