MDTS - MidoNet Distributed Testing System
=========================================

MDTS provides the testing framework for [MidoNet](https://github.com/midonet/midonet).

It will exercise MidoNet system using [Midonet Sandbox](https://github.com/midonet/midonet-sandbox)
(docker containers management framework) to simulate multiple hosts, including
Neutron, multiple MidoNet Agents, multiple Zookeeper and Cassandra instances
and Quagga servers.
On top of this simulated physical topology, we can generate whatever
virtual topology we need to test using both the Neutron API models (networks,
subnets, ports, security groups, etc.) or the internal Midonet API counterparts
(bridges, routers, ports, chains, etc.)
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
dependencies.

```
midonet/tests$ ./setup_test_server
```

If manual installation is needed, please refer to this script for a
comprehensive list of all the required packages.

You also need the python-neutronclient and the python-midonetclient installed
on your host so MDTS can access both API servers (Neutron or Midonet) through
the python API. However, as this package is part of the Midonet packages,
you need to install it once you compile/generate the packages.

To use the MidoNet sandbox, you must download the source from the github
repository into your midonet source tree:

From the root midonet directory:
```
git clone --recursive http://github.com/midonet/midonet-sandbox
```

Also, if the python-midonetclient package will be built from source, the
protobufs 2.6.1 compiler will also need to be installed.  If protobufs version
2.6.1 is not already installed, run these commands to install it:

```
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
```

Running Sandbox
---------------

To run MDTS, first start the Midonet Sandbox subsystem. Midonet Sandbox depends
on the MidoNet packages. If they're needed to be installed manually,
please build them as follows from the midonet root tree:

```
git submodule update --init --recursive
./gradlew clean
./gradlew -x test debian
find . -name "*.deb"
./midonet-cluster/build/packages/midonet-cluster_5.0~201509181011.8599820_all.deb
./midolman/build/packages/midolman_5.0~201509181011.8599820_all.deb
./python-midonetclient/python-midonetclient_5.0~201509181011.8599820_all.deb
./midonet-tools/build/packages/midonet-tools_5.0~201509181011.8599820_all.deb
```

It is recommended (almost mandatory if you don't want to suffer a python dependency
hell with the python libraries installed system wide) to install both MDTS and Sandbox
in a virtual environment. For that, just create a virtual environment and install
everything on it:

```
sudo pip install --upgrade pip setuptools virtualenv
virtualenv venv
source venv/bin/activate
pushd midonet-sandbox && python setup.py install && popd
pushd tests && pip install -r mdts.dependencies && popd
pushd python-midonetclient && python setup.py install && popd
```

The first line is needed since docker-py seems to fail with old versions of
setuptools.

If the protobufs compiler is not installed, the last command may fail.  If so,
please install the protobufs compiler as documented above.

Midonet Sandbox already use a predefined set of docker images to ease the task
of spawning different Midonet components. To start using sandbox and build the
initial set of images, you need to:

```
pushd tests
sandbox-manage -c sandbox.conf build-all default_v2_neutron+newton && popd
```

Wait until all images have been generated. The default_v2_neutron+newton
is a basic MDTS flavour with neutron for sandbox.
Look into the sandbox/flavours directory for a list of the supported
flavours. For more information about how to use sandbox, components,
flavors and overrides, check its [source repo](https://github.com/midonet/midonet-sandbox)
or execute `sandbox-manage --help`.

Copy all packages inside the corresponding override so Sandbox knows which
packages to install:
```
tests/copy_to_override.sh override_v2
```

And start sandbox with a specific flavor, override and provisioning scripts:
```
pushd tests
sandbox-manage -c sandbox.conf run default_v2_neutron+newton --name=mdts --override=sandbox/override_v2 --provision=sandbox/provisioning/all-provisioning.sh
```

To completely remove all containers to restart sandbox:
```
sandbox-manage -c sandbox.conf kill-all --remove
```

Running functional tests
------------------------

You're now set to run MDTS tests (remember to have the virtual environment activated):

```
pushd tests/mdts/tests/functional_tests
./run_tests.sh
```

Refer to documentation in [`run_tests.sh`][run_tests] for further information.

[run_tests]: mdts/tests/functional_tests/run_tests.sh
