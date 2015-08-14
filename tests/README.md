MDTS - MidoNet Distributed Testing System
=========================================

MDTS provides the testing framework for [MidoNet](https://github.com/midonet/midonet).

It will exercise MidoNet system using [Midonet Sandbox](https://github.com/midokura/midonet-sandbox) 
(docker containers management framework) to simulate multiple hosts, including 
multiple MidoNet Agents, multiple Zookeeper and Cassandra instances and Quagga 
servers. On top of this simulated physical topology, we can generate whatever 
virtual topology we need to test including ports, bridges, routers, chains, 
vlans, etc.
It will then run a suite of tests using python testing frameworks to inject 
traffic into the simulated network while simultaneously checking state and 
end-to-end transmission.

Minumum recommended environment
-------------------------------

* 8GB RAM
* 20GB storage space
* 2 CPUs (or VCPUs)

Prerequisites and MDTS package dependencies
-------------------------------------------

They can be installed automatically using the following script (only need
to run once). 
Most run-time dependencies are now handled inside the docker containers so the
software requirements for the host are basically related to compile-time
dependencies (e.g. protobufs). 

```
midonet/tests$ ./setup_test_server
```

If manual installation is needed, please refer to this script for a
comprehensive list of all the required packages.

You also need the python-midonetclient installed on your host so MDTS can 
access the API server through the python API. However, as this package is part
of the Midonet packages, you need to install it once you compile/generate
the packages.

Running Sandbox
---------------

To run MDTS, first start the Midonet Sandbox subsystem. Midonet Sandbox depends
on the MidoNet packages. If they're needed to be installed manually, 
please build them as follows:

```
midonet$ git submodule update --init --recursive
midonet$ ./gradlew clean
midonet$ ./gradlew -x test debian
midonet$ find . -name "*.deb"
./midonet-api/build/packages/midonet-api_2015.05~201506030403.f4646d4_all.deb
./midolman/build/packages/midolman_2015.05~201506030403.f4646d4_all.deb
./python-midonetclient/python-midonetclient_2015.05~201506030403.f4646d4_all.deb
./cluster/midonet-cluster/build/packages/midonet-cluster_2015.05~201506030403.f4646d4_all.deb
```

Midonet Sandbox already use a predefined set of docker images to ease the task
of spawning different Midonet components. To start using sandbox and build the
initial set of images, you need to:

```
midonet$ git clone https://github.com/midokura/midonet-sandbox
midonet$ pushd midonet-sandbox
midonet/midonet-sandbox$ sudo python setup.py install && popd
midonet$ pushd tests
midonet/tests$ sudo sandbox-manage -c sandbox.conf build-all default_v2 && popd
```

Wait until all images have been generated. The default_v2 is the default MDTS
flavour for sandbox. For more information about how to use sandbox, components,
flavors and overrides, check its [source repo](https://github.com/midokura/midonet-sandbox) 
or execute `sandbox-manage --help`.

Copy all packages inside the corresponding override so Sandbox knows which
packages to install:
```
midonet$ cp midolman/build/packages/midolman*deb tests/sandbox/override_v2/midolman
midonet$ cp python-midonetclient/python-midonetclient*deb tests/sandbox/override_v2/api
midonet$ cp cluster/midonet-cluster/build/packages/midonet-cluster*deb tests/sandbox/override_v2/api
```

And start sandbox with a specific flavor, override and provisioning scripts:
```
midonet$ pushd tests
midonet/tests$ sudo sandbox-manage -c sandbox.conf run default_v2 --name=mdts --override=sandbox/override_v2 --provision=sandbox/provisioning/bgp-l2gw-provisioning.sh
```

To completely remove all containers to restart sandbox:
```
midonet/tests$ sudo sandbox-manage -c sandbox.conf stop-all --remove
```

Running functional tests
------------------------

You're now set to run MDTS tests:

```
midonet$ pushd tests/mdts/tests/functional_tests
midonet/tests/mdts/tests/functional_tests$ ./run_tests.sh 
```

Refer to documentation in [`run_tests.sh`][run_tests] for further information.

[run_tests]: tests/mdts/tests/functional_tests/run_tests.sh
