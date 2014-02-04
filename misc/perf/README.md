## Motivation

This directory contains scripts for performance testing midolman. They are
aimed at measuring:

  * CPU usage
  * Memory usage
  * Throughput
  * Latency

across different configurations, environments and MidoNet topologies. They are
also intended to be automated and hooked up to Jenkins.

The scripts do not cover the possibility of having scenarios with tunnel flows
and multiple midolman instances.

The test script collects midolman metrics via JMX, using the jxmtrans daemon. It
generates traffic to pass through midolman using `nmap`.

## Anatomy of a test run

perftests.sh is the script that runs a performance test. On each run it will go
through the following phases:

* Redirect a copy of its own output to a logfile that will be collected at the
  end - `start_logging()`.
* Check that `$MIDONET_SRC_DIR` contains a checkout of the midonet repo with
  built debian packages. Gather build and environment information.
  `gather_build_info()`
* Stop midolman and jmxtrans, invoke the chosen scenario's cleanup method.
  `do_cleanup()`.
* Source the tests configuration, based on the hostname if a file for it exists
  in the profiles.d directory, or the default one otherwise. Test configuration
  dictates the length and packet rate for the tests.
* Install the midonet-api and midolman packages found in `$MIDONET_SRC_DIR`.
* Add two network namespaces and invoke the chosen scenario's `setup_topology`
  method. `create_scenario()`. See below for details on how to create a new
  scenario.
* Start jmxtrans.
* warm up midolman by passing traffic at a slow rate of 100pps. `warm_up()`
* run a throughput test. This test will send traffic in bursts of 65k packets.
  The first burst will go at 1k pps and the subsequent ones will increase the
  rate by 500 pps in each iteration. This phase will end when the rate exceeds
  `$THROUGHPUT_MAX_SCAN_RATE`. This is a configuration variable that can be
  tuned per profile. `test_throughput()`
* Run a longer running test at `$LONG_RUNNING_SCAN_RATE`. These are 50k packet
  bursts repeated over `$LONG_RUNNING_SCAN_ITERATIONS`. This phase dominates
  the length of the tests. Adjust accordingly when quickly iterating through
  test runs. `long_running_tests()`
* Stop jmxtrans.
* Collect a midolman heapdump.
* Stop midolman and tear down the scenario.
* Create the graphs and collect all data for the report.
* Post everything to the reports server.

The reports server will automatically pick up the posted data and display the
test run.

## The test reports server

Its address is: http://perftests.midokura.com:8080

The SSH key that perftest.sh uses to post reports is found under the midokura
account at 176.28.127.122. Copying this key or adding a new public key to the
server is needed when adding a new VM that should be able to post reports.

## Setting up a performance test environment

The following dependencies need to be installed:
* All of midolman dependencies
* All of midonet-api dependencies
* Python-midonetclient
* Nmap
* Ovs kmod
* Jmxtrans (jmxtrans package is available in ~/debs/ directory at 176.28.127.122.)
* RRDtool

Also,
* Zookeeper and Cassandra are installed and running (starting/stopping them is a
  potential point of improvement).
* `$MIDONET_SRC_DIR` points to a directory containing a MidoNet repository
  checkout and built packages (this condition is what needs to change for
  jenkins integration).
* JSON_DIR in qa/perf/profiles.d/default/jmxtrans/default needs to point to
  `$CONFDIR`/jmxtrans/json.
* Similarly, template file locations specified in qa/perf/jmxtrans/json/midolman.json
  need to point to corresponding template files under `$CONFDIR`/jmxtrans/templates.
* the SSH key mentioned above is required to post results to
  perftests.midokura.com. (Copy 176.28.127.122:/home/midokura/id_rsa_testreports to
  /home/midokura/ on the local machine.)

## Running a performance test

Tests can be run like this:

`# ./perftests.sh <topology_name>`

The only argument is the name of the topology, which should refer to a topology
file present on this same directory.

The following command line runs a performance test on the most basic topology:

`# ./perftests.sh basic_topology`

Because tests run for a while, it's often useful to keep a loose eye on the
output and to run them under hohup, screen or similar.

## Adding a profile for a new host

Per-host configurations are stored in the `profiles.d` directory. A profile
directory may contain configuration files for midolman and a `perftests.conf`
file to change the behaviour of the test runs on the given host through three
environment variables, explained above:

+ `THROUGHPUT_MAX_SCAN_RATE`
+ `LONG_RUNNING_SCAN_ITERATIONS`
+ `LONG_RUNNING_SCAN_RATE`

## Adding a new topology

Adding a new topology is done by writing a bash script that will be sourced
by the main perftests.sh script. The topology script is expected to provide:

* `setup_topology()`, `tear_down_topology()` and `print_topology()` shell
  functions. These will be invoked by the main script at the proper stages.
* Environment variables indicating IP addresses for the source and destionation
  of test traffic. These are: `TOPOLOGY_SOURCE_HOST`, `TOPOLOGY_DEST_HOST`,
  `TOPOLOGY_SOURCE_NET`, `TOPOLOGY_DEST_NET`.

In turn, the main script provides two variables with the two network interfaces
that the topology script should bind to its virtual devices:
`TOPOLOGY_SOURCE_BINDING` and `TOPOLOGY_DEST_BINDING`.

See `basic_topology` for a complete example that creates a simple l2 topology.
