## Precis

**MidoNet** is a system which implements an abstract, software-defined
virtual network atop an existing IP fabric.  That means that packets that
enter a MidoNet deployment will then leave it at the location and with the
alterations they would have had if they'd actually traversed the networking
equipment defined in the deployment's virtual topology, or as close to it
as we can manage.

## Overview

A MidoNet deployment consists a few kinds of nodes, all connected in
an IP network.  Many nodes run **Midolman**, and are where traffic enters
and leaves.  Traffic is sent from its entry point (the *ingress node*) to
its exit point (the *egress node*) via tunnels; see [the tunnel management
document](docs/tunnel-management.md).  The network's virtual topology
and associated information is stored in ZooKeeper, and per-connection
state which is shared between nodes is stored in Cassandra -- all the
Midolman nodes which compose a virtual network must have connectivity to
the ZooKeeper and Cassandra clusters, as that's how they coordinate.
The web-based REST API described [here](docs/rest-api-specification.md)
is provided by nodes running MidoNet's API service in a webserver.  These
API server nodes will read and modify the virtual topology configuration,
so they need to have connectivity to the ZooKeeper cluster, but not
necessarialy any other node types.


## Organization of the project

The **MidoNet** project is split into several submodules:

### brain

This contains the various pieces that compose the Midonet controller
node `brain`. Controller nodes take care of orchestrating the
configuration of all Midonet subcomponents, as well as coordinating with
other external devices and systems, such as VTEP switches, the backend services
holding our Network State DataBase (e.g: Zookeeper and Cassandra), etc.

### packets

This holds basic classes for parsing, building, and manipulating packets
of various network protocol types.

### midonet-util

Contains basic utilities used by the other modules, and is described
[here](docs/midonet-util.md).

### midolman

Contains the *MidoNet* edge controller code, as described [here](docs/midolman.md).

### midonet-api

Contains the implementation of the *MidoNet* REST API.

### netlink

Code for speaking the netlink protocol over a netlink socket, generally
for communicating with the OS kernel.

### odp

Code for interacting (receiving notifications and sending commands) to
the kernel's Open Datapath module.


## Building the project

The `brain` directory contains an odl-ovsdb git submodule with code that
must be compiled in order to generate dependencies needed in various
midonet components. Before any build tasks, ensure that you have the
right version of odl-ovsdb by executing:

    ~/midonet$ git submodule update --init --recursive

### Complete build

    ~/midonet$ mvn

This will build all the modules while running all the tests from all the modules.
To skip the tests, you can run the command:

    ~/midonet$ mvn -DskipTests

### Distro packages

By default, the mvn build will only generate debian packages for midolman and
midonet, which can be found respectively in midolman/target and
midonet-api/target directories. To build rpm packages you should run

    ~/midonet$ mvn -Drpm -DskipTests

For a systemd-based system (e.g. RHEL 7),

    ~/midonet$ mvn -Drpm-sysd -DskipTests

On ubuntu this requires the rpm tools which you can install with

    # apt-get install rpm

rpm packages for midolman and midonet will be found in
target/rpm/midolman/RPMS/noarch directories of midolman/ and midonet-api/
subprojects respectively.

### Versioning

To change the version number consistently across pom files in all subprojects,
you can run the command

    $ mvn versions:set -DnewVersion=x.y.z-whatever_tag

This will create backup pom files. If you are happy with the change, you can
remove these backup files with

    $ mvn versions:commit

or if you want to revert your changes

    $ mvn versions:revert

### Build all & Run tests

    ~/midonet$ mvn clean test

This will build all the modules and run all the test (but it will skip all the
functional tests)


## Intellij Tips

If you use Intellij the following variables can be useful
* $PROJECT_DIR$
* $APPLICATION_HOME_DIR$
* $MODULE_DIR$
* $USER_HOME$
