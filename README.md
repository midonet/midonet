
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
The web-based REST API described [here][rest-api] is provided by nodes running
MidoNet's API service in a webserver.  These API server nodes will read and
modify the virtual topology configuration, so they need to have connectivity to
the ZooKeeper cluster, but not necessarily any other node types.

[rest-api]: http://docs.midonet.org/docs/latest/rest-api/
    "MidoNet API Specification"

## How to contribute

You can report a bug using [MidoNet Issue tracking][jira].

All patches to MidoNet are submitted to Gerrit, an open source,
web-based code review system. It's publicly hosted on
[GerritHub][gerrithub], and integrated with a CI infrastructure based on
Jenkins that runs several suites of automated tests.

To submit a patch, you'll need to sign into GerritHub using your GitHub
account and set up your ssh-key in `Settings->SSH public keys`. If you
need information to set up git review, or git-review workflows, please
check MidoNet Developer's Guide in the [wiki][dev-guide].

Submitting a review is simple, typically:

    git clone https://github.com/midonet/midonet
    git checkout -b your_branch origin/master
    # .. make some changes ..
    git commit -as  # commit your changes, and sign off your commit
    git review master

Your review will now appear on [GerritHub][gerrithub]. After committers
approve your change and the code is tested, it'll get merged into the
main repository.

Feel free to join other MidoNet developers on our public
[Slack chat channel][slack], or our Development
[mailing list][dev-mail]. We'll be happy to help you get set up.

[jira]: http://midonet.atlassian.net
    "MidoNet Issue tracking"
[gerrithub]: https://review.gerrithub.io/#/q/project:midonet/midonet
    "GerritHub"
[dev-guide]: http://wiki.midonet.org/Developer%27s%20Guide
    "MidoNet developers guide"
[slack]: http://slack.midonet.org
    "MidoNet Public Slack"
[dev-mail]: http://lists.midonet.org/listinfo/midonet-dev
    "MidoNet developers mailing list"

## Organization of the project

The **MidoNet** project is split into several submodules:

### cluster

This contains the various pieces that compose each of the nodes in
Midonet's distributed controller (`cluster`). Controller nodes take care
of orchestrating the configuration of all Midonet subcomponents, as well
as coordinating with other external devices and systems, such as VTEP
switches, the backend services holding our Network State DataBase (e.g:
Zookeeper and Cassandra), etc.

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

## Build dependencies

Most dependencies are pulled in by the gradle build scripts, however
there are some prerequisites:

* java 7 jdk
* protobufs compiler
    * Install 2.6.1 from [here](https://github.com/google/protobuf/releases)
* fpm (ruby gem) to build debian/rpm packages
* rpm

## Building the project

The `cluster` directory contains an odl-ovsdb git submodule with code that
must be compiled in order to generate dependencies needed in various
midonet components. Before any build tasks, ensure that you have the
right version of odl-ovsdb by executing:

    ~/midonet$ git submodule update --init --recursive

### Complete build

    ~/midonet$ ./gradlew

This will build all the modules while running all the tests from all the modules.
To skip the tests, you can run the command:

    ~/midonet$ ./gradlew -x test

### Distro packages

The build script provides targets to build debian and rpm packages. In all cases
packages will be found in midolman/build/packages/ and midonet-api/build/packages.

Building debian packages:

    ~/midonet$ ./gradlew debian -x test

RPM packages targeted for RHEL 6.5:

    ~/midonet$ ./gradlew rpm -x test

RHEL 7 packages:

    ~/midonet$ ./gradlew rpm -x test -PrhelTarget=7

SLES 12 packages:

    ~/midonet$ ./gradlew rpm -x test -PrhelTarget=sles12

On ubuntu this requires the rpm tools which you can install with

    # apt-get install rpm

### Build all & Run tests

    ~/midonet$ ./gradlew test
