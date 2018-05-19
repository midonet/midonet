
## Introduction

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

[rest-api]: https://docs.midonet.org/docs/latest/rest-api/
    "MidoNet API Specification"

## How to contribute

You can report a bug using [MidoNet Issue tracking][jira].
**NOTE**: Please use "MidoNet (MNA)" project to report any MidoNet bugs.
Other MidoNet related projects are merely historical and not actively used anymore.

All patches to MidoNet are submitted to Gerrit, an open source,
web-based code review system. It's publicly hosted on
[GerritHub][gerrithub], and integrated with a CI infrastructure based on
Jenkins that runs several suites of automated tests.
**NOTE**: At this point, the CI logs are available only to Midokura internal.
We consider it a problem. (MNA-1209)

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
[dev-guide]: https://github.com/midonet/midonet/wiki/Developer%27s-Guide
    "MidoNet developers guide"
[slack]: https://join.slack.com/t/midonet/shared_invite/enQtMjU2NzAzNzIyNjc0LWYzZTczYmVkZjkxMDllOWUxMGVjNGI1NjNlZDM1Y2JhZGMyZDNkMGVlNWE2ZWU0ZmJlNDFhMWRmMGRjODE5YjA
    "MidoNet Public Slack"
[dev-mail]: http://lists.midonet.org/listinfo/midonet-dev
    "MidoNet developers mailing list"

### Commit messages

It's strongly recommended to describe the whole relevant information
in the commit message itself, rather than just having a reference to
a bug tracker, especially when the tracker is not publicly available.

It's required to include a reference to the related jira issue in
a commit message:

    Ref: MNA-xxx

Otherwise, the CI will complain by putting "Verified -1" on the patch.
For trivial patches which don't worth jira issues, you can put the following
string instead:

    Ref: MINOR_CHANGE

#### Bug references

There are a few kinds of references to bug trackers commonly found
in the commit messages.

[MidoNet Issue tracking][jira]:

    MNA-xxx
    MNP-xxx
    MND-xxx
    https://midonet.atlassian.net/browse/xxxxx

[Launchpad][launchpad]:

    Closes-bug: #xxx
    https://bugs.launchpad.net/yyyyy/+bug/xxxxx

Midokura jira (private to the company):

    MI-xxx
    MN-xxx
    IN-xxx
    QA-xxx
    https://midobugs.atlassian.net/browse/xxxxx

Midokura github issues (private to the company, no longer used):

    #xxx
    GH #xxx
    Issue #xxx

Midokura redmine (no longer available):

    #xxx
    bug xxx
    http://redmine.midokura.com/redmine/issues/xxx

[launchpad]: https://bugs.launchpad.net/
    "Launchpad.net"

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

### netlink

Code for speaking the netlink protocol over a netlink socket, generally
for communicating with the OS kernel.

### odp

Code for interacting (receiving notifications and sending commands) to
the kernel's Open Datapath module.

## Build dependencies

Most dependencies are pulled in by the gradle build scripts, however
there are some prerequisites:

* fpm (ruby gem) to build debian/rpm/tar packages
* java 8 jdk
* rpm
* ronn to build man pages
* gcc to build native libraries

**NOTE**: The gcc version installed should be 4.8.5 because the test framework
used to run unit tests over the native libraries that MidoNet uses requires
the code to be compiled with this version.

You will also need to install the protobufs compiler, version 2.6.1 from
[here](https://github.com/google/protobuf/releases/tag/v2.6.0).  Use
these commands:

    wget https://github.com/google/protobuf/releases/download/v2.6.1/protobuf-2.6.1.tar.gz
    tar -xzf protobuf-2.6.1.tar.gz
    cd protobuf-2.6.1
    ./configure
    make
    sudo make install
    sudo ldconfig
    cd -
    rm -rf protobuf-2.6.1
    rm protobuf-2.6.1.tar.gz

## Testing tools

*MidoNet* is tested at both an integration and a functional level by the
MDTS (Midonet Distributed Testing System), which can be found in the
midonet repository at

http://github.com/midonet/midonet/tree/master/tests

## Building the project

### Complete build

    ~/midonet$ ./gradlew

This will build all the modules while running all the tests from all the modules.
To skip the tests, you can run the command:

    ~/midonet$ ./gradlew -x test

### Unit tests

To run unit tests, you can run the command:

    ~/midonet$ ./gradlew test

You can run tests only for a specific module.
For example:

    ~/midonet$ ./gradlew midolman:test

You can specify a specific test class to run.
For example:

    ~/midonet$ ./gradlew midolman:test -Dtest.single=HmacTest

Unit tests generate HTML reports under build/ directory:

    ~/midonet$ open ./midolman/build/reports/tests/index.html

### Coverage report

The following command generates coverage report running unit tests
and integration tests:

    ~/midonet$ ./gradlew cobertura

You can exclude integration tests as the following.  It can be useful
when running tests on non supported platforms like OS X:

    ~/midonet$ ./gradlew cobertura -x integration

The generated report will be available under build/ directory:

    ~/midonet$ open ./midolman/build/reports/cobertura/index.html

### Distro packages

The build script provides targets to build debian, rpm and tar packages. In
all cases packages will be found in the following directories:

    midolman/build/packages
    midonet-cluster/build/packages
    midonet-tools/build/packages
    python-midonetclient

On ubuntu you can install required packages with:

    # apt-get install ruby-dev
    # apt-get install ruby-ronn
    # gem install fpm

Building debian packages:

    ~/midonet$ ./gradlew debian -x test

RHEL 7 packages:

    ~/midonet$ ./gradlew rpm -x test

SLES 12 packages:

    ~/midonet$ ./gradlew rpm -x test -PrhelTarget=sles12

On ubuntu this requires the rpm tools which you can install with

    # apt-get install rpm

Tarballs:

    ~/midonet$ ./gradlew tar -x test

### Build all & Run tests

    ~/midonet$ ./gradlew test

## Running the Cluster Daemon locally

Simply execute the following command from the root directory of the clone:

    ./gradlew midonet-cluster:run

You can specify a custom configuration file by adding:
"-Pconf=<path_to_config_file>". Note that the configuration file path is
relative to the midonet-cluster submodule, i.e., 
-Pconf=midonet-cluster/conf/<config_file>.

The Cluster will try to write on /etc/midonet_host_id.properties.  If
this path is not writable you can either make it so, or override adding:

    -Dmidonet.host_id_filepath=/tmp/midonet_host_id.properties

to the gradlew command.
