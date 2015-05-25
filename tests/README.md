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

#### Minumum Recommended Hardware:

* 8GB RAM
* 20GB HDD
* 2 CPUs (or VCPUs)
* Ethernet network device (REQUIRED)
* Terminal access via SSH (recommended)

### MDTS Package Dependencies

These can be installed automatically using the:

    ./setup_test_server

script.  But, if manual installation is needed, the following packages
are required.  Please refer to this script for a comprehensive list of
all the required packages.

To run MDTS, first start the MMM (Multiple MidolMan) subsystem.  This
will spawn a container-based cluster.

    cd mmm
    ./init
    ./boot

You're now set to run MDTS tests:

    cd ../mdts/tests/functional_tests
    ./run_tests.sh

Refer to documentation in the run_tests.sh file for further information.
