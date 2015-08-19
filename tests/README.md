MDTS - MidoNet Distributed Testing System
=========================================

MDTS provides the testing framework for MidoNet.

MidoNet can be found at http://github.com/midonet/midonet

It will exercise MidoNet system using contained namespaces to
simulate multiple hosts, including multiple MidoNet Agents, multiple
Zookeeper and Cassandra instances, routers, Virtual LANs, and an
OpenStack network host.  It will then run tests using python testing
frameworks to inject traffic into the simulated network while
simultaneously checking state and end-to-end transmission.

Minumum recommended environment
-------------------------------

* 8GB RAM
* 20GB storage space
* 2 CPUs (or VCPUs)

Prerequisites and MDTS package dependencies
-------------------------------------------

They can be installed automatically using the following script.

```
midonet/tests$ ./setup_test_server
```

If manual installation is needed, please refer to this script for a
comprehensive list of all the required packages.

Running MMM
-----------

To run MDTS, first start the MMM (Multiple MidolMan) subsystem. MMM depends on
the MidoNet packages. If they're needed to be installed manually, please build
and install them as follow:

```
midonet$ git submodule update --init --recursive
midonet$ ./gradlew clean
midonet$ ./gradlew -x test debian
midonet$ find . -name "*.deb"
./midolman/build/packages/midolman_2015.05~201506030403.f4646d4_all.deb
./python-midonetclient/python-midonetclient_2015.05~201506030403.f4646d4_all.deb
./cluster/midonet-cluster/build/packages/midonet-cluster_2015.05~201506030403.f4646d4_all.deb
midonet$ find . -name "*.deb" | xargs sudo dpkg -i
```

Then run the following scripts that will spawn a container-based cluster.

```
midonet/tests$ cd mmm
midonet/tests/mmm$ ./init
midonet/tests/mmm$ ./boot
```

Running functional tests
------------------------

You're now set to run MDTS tests:

```
midonet/tests/mmm$ cd ../mdts/tests/functional_tests
midonet/tests/mdts/tests/functional_tests$ ./run_tests.sh -r ../../../
```

Refer to documentation in [`run_tests.sh`][run_tests] for further information.

[run_tests]: tests/mdts/tests/functional_tests/run_tests.sh
