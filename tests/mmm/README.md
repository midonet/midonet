MMM (Multiple MidolMan in a single host)
========================================

MMM (Multiple Midolman in a Single Host) is an effort to run multiple midolmans
in a single physical host. You can construct even a full-featured MidoNet,
including ZooKeeper cluster, Cassandra cluster, BGP uplinks and L2 gateway.
Currently, MMM are tested in three running environments:

1. Native Linux environment in physical host
2. virtual machine with VirtualBox and Vagrant
3. AWS EC2

Prerequisites
-------------

You need to install Vagrant and its dependencies. I'd recommend to use Virtual
Box but you can use any other [supported providers][providers] as well.

* [Virtual Box](https://www.virtualbox.org/wiki/Downloads)
* [Vagrant](http://downloads.vagrantup.com/)

[providers]: http://docs.vagrantup.com/v2/getting-started/providers.html

Usage
-----

### Up and Running the Vagrant Box

```bash
$ cd /qa/mmm/vagrant/ubuntu-12.04
/qa/mmm/vagrant/ubuntu-12.04$ vagrant up default
...
```

### Halting Vagrant Box

```bash
/qa/mmm/vagrant/ubuntu-12.04$ vagrant halt
```

References
==========

* [Midokura Wiki entry for MMM][wiki]
* [How to use Vagrant box for CP][cp]

[wiki]: https://sites.google.com/a/midokura.com/wiki/technical-notes/mmm-how-to-run-it-and-how-it-works
[cp]: https://github.com/midokura/midonet-cp#setup-the-api-sandbox-in-the-local-environment-optional-for-devs
