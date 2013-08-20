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

### midonet-functional-tests

Contains a set of functional tests that validate the basic functionality of
*MidoNet*.  There is significant setup needed to create an environment where
the functional tests can run.

## Building the project
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

### Build all & Run functional tests

    ~/midonet$ mvn -DjustFunctionalTests clean test

This will build all the modules but only run the tests from the
midonet-functional-tests module.

### Build all & Run a functional test

    ~/midonet$ mvn -DjustFunctionalTests test -Dtest=BridgeTest

This will build all the modules but only run the tests from the
midonet-functional-tests module.

If you run tests with the embedded version of Midolman, you need to run them as root because Midolman opens a Netlink socket:

    ~/midonet$ sudo mvn -Dmaven.repo.local=/home/midokura/.m2/repository/ test -Dtest=YourTest -DfailIfNoTests=false

If the test launches Midolman out of process (e.g. some functional tests), then password-less sudo for /usr/bin/java must be enabled for your user.
The functional tests also create interfaces and modify them, so password-less sudo for /sbin/ip must also be enabled for your user.

### Build all & Run tests (but skip the functional tests)

    ~/midonet$ mvn -DskipFunctionalTests clean test

This will build all the modules and run all the test (but it will skip all the
functional tests)

### Running with a remotely managed host.

In order to run a local controller one would need to provide a file named
*managed_host.properties* somewhere in the classpath of the running process
(being it a test case or midolman).

This file has the following syntax:

    [remote_host]
    specification = <user>:<pass>@<host>:<port>

    [forwarded_local_ports]
    12344 = 12344   # OpenvSwitch database connection port
    9090 = 9090     # Midonet Sudo Helper connection
    6640 = 6640     #

    [forwarded_remote_ports]
    6650 = 6650     # 1'st midolman controller connection
    6655 = 6655     # 2'nd midolman controller connection

    [midonet_privsep]
    path = <remote_target_path>/midonet-privsep

Everything except the \[remote_host\] section is optional.
Midolman at the beginning will try to see of such a file exists
(via a call to RemoteHost.getSpecification().isValid()) and if such a file exists
then the RemoteHost class will parse the file and setup the required port
forwardings over a new ssh session to the remote_host.specification address.

Each call to ProcessHelper.newProcess() will check to see if a specification has
been read already and if that specificaiton is valid it will execute all the
calls via a ssh tunnel instead of using a local Process.getRuntime().exec() call.

The port fowarding ensure that the local controller can communicate with remote
services and viceversa and the remote execution ensure that what we want to be
executed and needs access to the remote machine actually has access to it by the
virtue of being executed remotely.

## Intellij Tips
If you use Intellij the following variables can be useful
* $PROJECT_DIR$
* $APPLICATION_HOME_DIR$
* $MODULE_DIR$
* $USER_HOME$
