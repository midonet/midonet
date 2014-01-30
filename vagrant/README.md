## "one click" midolman VM

Vagrant made, Ubuntu 12.04 based, and featuring:
- building artifacts from source and running unit tests
- running midolman and mm-* utilities from built artifacts
- openvswitch 1.10 kernel module
- zookeeper, cassandra, tomcat7 services
- all necessary dependencies for running performance tests

### Prerequisites

Vagrant and virtualbox.

### Building and using a midolman VM

To create a vm, from this directory do: `$ vagrant up`

Vagrant will load the profil in `Vagrantfile` and build the vm, which takes a
couple of minutes. When the vm is running, Vagrant will automatically execute
`vagrant_init.sh` inside the vm. This script is run once only. You can execute
it again with `$ vagrant reload`.

After this initial vm setup, you can log into the vm with `$ vagrant ssh`, you
can stop the vm with `$ vagrant suspend`, and bring it back with `$ vagrant up`.

Inside the vm, you can go to /midonet to access the project and run maven
commands from there as usual. You can run midolman normally using the maven
exec:exec goal or run the midonet-api server with the jerry:run goal. Port
forwarding is set by default with ports 4000 (midolman remote debugging) and
8080 (midonet-api). See docs/build-maven.md for details.

The vm settings are changed in `Vagrantfile` (cpu, memory, ...).

It is possible to run the perftest inside the vm. For this the folder mapping
settings between host an guest has to be changed in Vagrantfile. Follow the
indications there. It is also recommended to increase the vm memory settings.

### SSH forwarding

Instead of running zookeeper and cassandra inside the vm, it is also possible
to run them on the host or remotely. To allow midolman to connect to these
services from within the vm, log into the vm with reverse port forwarding:

`$ vagrant ssh -- -R guest_port:remote_ip:remote_port`

For example, to allow midolman to connect on your host zookeeper, run

`$ vagrant ssh -- -R 2181:localhost:2181`

(Unfortunately this settings does not work with zinc for compiling source files
on the host from within the vm: zinc does not support remote compilation.)

### Folder sharing

Vagrant mounts some subfolders of the midonet repo to emulate a midolman package
installation on the vm:
- midonet/midolman/conf is mounted at /etc/midolman for configuration files
- midonet/midolman/target is mounted at /usr/share/midolman for jar artifacts
This allows mm-dpctl and mm-ctl to work normally using source files and
artifacts build from source files.

The host user's .m2 maven local artifact repository is also mounted inside the
vm so that maven works inside the vm without further downloads.

### Package installation

To be able to install zookeeper 3.4.5 and openvswitch 1.10 on ubuntu 12.04,
`vagrant_init.sh` uses "package pinning" with packages from ubuntu raring and
saucy. You can modify these settings by looking at the `preferences` apt file
in this directory. To apply changes, do `$ vagrant reload` to override the apt
settings in the vm.

Some java based apps are installed from downloaded binaries rather than using
the package system (maven, zinc). This simplify java 7 settings. The dl urls are
found at the top of `vagrant_init.sh`.

### Known issues

Accordting to your platform and host settings, the vm may have trouble to do
name resolution. In this case, you should turn on the dns resolver option in
Vagrantfile.
