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

```mdts_cbt env```

command.  But, if manual installation is needed, the following packages
are required.

Install the following using Apt-Get or DPKG (Ubuntu Package Manager):
* git
* wget
* curl
* g++
* bridge-utils
* iptables
* haproxy
* quagga
* software-properties-common
* screen
* tmux
* libncurses5-dev
* openjdk-7-jdk
* debhelper
* autotools-dev
* python-unittest2
* python-all-dev
* ruby-ronn
* ruby1.9.1-dev
* ruby1.9.1-full
* rpm
* zookeeper
* zookeeperd
* zkdump
* iproute
* python-software-properties
* dsc20 (version 2.0.10-1)
* cassandra (version 2.0.10)
* openvswitch-datapath-dkms (version 1.10.2-0ubuntu2~cloud0)
* tomcat7
* python-httplib2
* python-webob
* python-eventlet
* dnsmasq-base
* python-setuptools
* python-pip
* python-numpy
* mz
* libyaml-dev libpython-dev
* linux-image-extra-3.13.0-45-generic (depends on specific `uname -r` value)

Install the following with GEM (Ruby Package Installer):
* fpm

Install the following with PIP (Python Package Installer):
* pyyaml
* futures
* nose
* pyhamcrest

Install the following manually (only if you are building MidoNet from
source):
* protobufs compiler (version 2.6.1)

Configuring and Running MDTS: mdts_cbt
--------------------------------------

The mdts_cbt script is generally used to configure, build, and run the
tests for the MDTS system.  It has several options which affect these
three operations.  The basic three operations are common to any testing
environment, and they are as follows:

* Configure
  * Set up the base system environment
  * Configure the system itself to prepare for building, installing,
    and testing the application
* Build
  * Decide where to get the source and test code
  * Pull it to the system
  * Build the source and tests
  * Install the application(s)
* Test
  * Configure the application
  * Set up the testing runtime
  * Run the applications in the test environment
  * Run the tests and gather the results
  * Report the results

MDTS is specifically targeted to use MidoNet as the application, with
separate hosts being simulated with ip net namespaces.  It will, by
default, install various packages used for the system environment (as
above, etc.) as part of the configure step.

###Basic Usage

```./mdts_cbt <target> <parameters>```

Target      | Parameters                  | Description
:-----------|:----------------------------|:-----------------------------------------------
git         | ```<repo> <branch>``` | Pulls MidoNet source from git (use -git-server flag to set different GIT server, default is "github.com"), builds it, and installs the built packages.  ```<repo>``` should be in the form of foo/bar.
source      | ```<src_dir>```             | Builds MidoNet from source already on disk and installs the package.  ```<src_dir>``` should point to the already-existing source.
file        | ```<pkg_dir>```             | Installs MN packages from package files already on disk.  ```<pkg_dir>``` should point to a root directory containing all the packages to be installed. The script will then search for any *.deb files in that directory tree (including subdirectories) and install them using DPKG.
package     | ```<repo> <dist>```   | Pulls the MidoNet packages (no source) from an apt-get repo.  ```<repo>``` should point to the repository name (only give the last identifier, such as ‘nightly’ or ‘stable’) and ```<dist>``` is the component name (usually ‘main’, but can be ‘jno’ or ‘icehouse’, etc.).
artifactory | ```<repo> <dist>```   | Pulls the MidoNet packages (no source) from an artifactory repo.  ```<repo>``` should point to the artifactory repository name (only give the last identifier, such as ‘nightly’ or ‘stable’) and ```<dist>``` is the component name (usually ‘main’, but can be ‘juno’ or ‘icehouse’, etc.).
mdts        | One of: start, test, testd, stop, clean | Directly control the MDTS system: <ul><li>start - Initializes and boots the MDTS system, making it ready to run tests.</li><li>test - Initializes and boots the MDTS system, and also runs all the configured tests.</li><li>testd - Just runs the tests directly without starting or touching the MDTS state.</li><li>stop - Stops the MDTS system and cleans up the running processes.</li><li>clean - Stops MDTS and uninstalls the MN packages, cleaning the system state.</li>
env         | None                        | Directly set up the testing environment by installing the prerequisite packages (see "Pre-requisites" above).


There are also several flags which alter the runtime behavior:

Flag                      | Description
:-------------------------|:-----------
-h                        | Print the usage and help screen
-tr ```<dir>```           | Sets the test root to ```<dir>``` (defaults to mdts/tests, with root directory being the location of the mdts_cbt script)
-tc ```<test_category>``` | Limit to given category listed in ```<test_root/test_categories>```
-t ```<tests>```          | Limit to given tests (defaults to all tests, multiple -t flags allowed to specify multiple tests).  Tests can be any python module name in the test root directory.
-x ```<tests>```          | Tests to exclude in run against the specified target in quotes (multiple -x flags allowed to exclude multiple tests)
-n                        | Dry run: print commands that would be run but don't proceed
-ne                       | Do not install base system environment
-nt                       | Do not run tests, only fetch/build/install MN
-ni                       | Do not install MN packages after building source
-nm                       | Do not start and stop MDTS (will still try to run tests)
-git-ssh ```<user>```     | Use SSH to connect to GIT server as ```<user>```
-git-server ```<srv>```   | Set the GIT hub server to use for source (default is github.com)


###Examples

**Running All T3 Tests on Local Box (pull OSS from git)**

```./mdts_cbt git midonet/midonet master -ne```

**Running All T3 Tests on Local Box (use ssh to access git)**

```./mdts_cbt git midonet/midonet master -ne --git-ssh git```

*Note that you must have your public key installed on github.com with
access to the repository for this to work*

**Running All T3 Tests on Local Box(MN source in /home/src/midonet)**

```./mdts_cbt source /home/src/midonet -ne```

**Running Gate T3 Tests on Local Box(MN source in /home/src/midonet)**

```./mdts_cbt source /home/src/midonet -ne -tc gate```

**Running Only Bridge T3 Test on Local Box(MN source in /home/src/midonet)**

```./mdts_cbt source /home/src/midonet -ne -t test_bridge```

**Running Midonet T2 Tests on Local Box(MN source in /home/src/midonet)**

```./mdts_cbt source /home/src/midonet -ne -tr /home/src/midonet/tests/mdts/tests/functional_tests```

**Running All T3 Tests On OSS Master Source Branch in CI**

```./mdts_cbt git```

**Running All T3 Tests on OSS Packages from Artifactory**

```./mdts_cbt artifactory```

**Running All T3 Tests on Packages from Artifactory Using Login**

```./mdts_cbt artifactory <art-server>/artifactory/<dist> <component> --auth-user <user> --auth-pass <pass>```

*Note: The user and pass must be clear text and must have access to the
repo on the artifactory server.  Other authentication methods are available,
settable via the ‘--auth-method’ flag, but ‘http’ is the default.*

**Setting up the Test Environment With Necessary Dependencies**

```./mdts_cbt env```

**Running All T3 Tests Directly Without Building/Installing MidoNet**

```./mdts_cbt mdts test```

**Running All T2 Tests Directly Without Building (MN source in /home/src/midonet)**

```./mdts_cbt mdts test -tr /home/src/midonet/tests/mdts/tests/functional_tests```

**Starting the MDTS System Without Running Tests**

```./mdts_cbt mdts start```

**Running All T3 Tests Directly**

```./mdts_cbt mdts testd```

**Stopping the MDTS System**

```./mdts_cbt mdts stop```

**Stop MDTS and Remove all MidoNet Packages**

```./mdts_cbt mdts clean```

**Stop MDTS and Remove all MidoNet Packages and Clean the Directory Trees**

```./mdts_cbt mdts cleanall```
