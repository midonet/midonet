DevMido
=======

DevMido is a set of scripts to set up MidoNet on a single host to simplify
the development of MidoNet.

It is inspired by OpenStack's
[DevStack](https://github.com/openstack-dev/devstack), and indeed much of
DevMido's scripts were shamelessly copied over from DevStack.


Requirements
------------

Platform: Ubuntu 14.04 on x86_64
RAM: 4GB(minimum), but when running this script in
     conjunction with devstack, more memory is recommended.
     8GB should be big enough for spawning several VMs.


Running DevMido
---------------

Because DevMido may modify your system (such as installing new packages),
it is recommended that you run it inside a dedicated VM or cotainer.

To run:

    ./devmido.sh


Stopping DevMido
----------------

To stop the running session of DevMido:

    ./undevmido.sh


Customizing DevMido
-------------------

`midonetrc` defines the configurable environment variables in `devmido.sh`, and
their default values.

You can override these variables by creating a file called `localrc` in the
same directory and setting new values for them.
