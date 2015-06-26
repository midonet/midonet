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

    ./mido.sh


Stopping DevMido
----------------

To stop the running session of DevMido:

    ./unmido.sh


Customizing DevMido
-------------------

`midorc` defines the configurable environment variables in `mido.sh`, and
their default values.

You can override these variables by creating a file called `localrc` in the
same directory and setting new values for them.


Setting Up Fake Uplink
----------------------

Once the MidoNet is running properly, you can set up a fake uplink on the
host.  The only requirement is that there is a router named
'MidoNet Provider Router' exists in the system that acts as the virtual gateway
between MidoNet and the host.

To allow connectivity from the host to MidoNet through the gateway router,
the following topology is created:

<pre>
            +---------------+
                            |
                            | 172.19.0.1/30
         +------------------+---------------+
         |     Fake Uplink Linux Bridge     |
         +------------------+---------------+        'REAL' WORLD
                            | veth0
                            |
                            |
                            | veth1
    +------+  +-------+  +-------------+  +-----+  +-----+
                            |
                            |
                            |
              172.19.0.2/30 | uplink port
         +------------------+----------------+        'VIRTUAL' WORLD
         |     MidoNet Provider Router       |
         +------------------+----------------+
                            |  200.200.200.0/24 (provided by user)
                            |
            +---------------+----------------+
                                        virtual network

</pre>

To set up the scenario above:

    ./create_fake_uplink.sh 200.200.200.0/24

You can also set the CIDR environment variable instead of passing it in as an
argument.

If no argument is passed in, and CIDR is not set, then it defaults to
'200.200.200.0/24'.

To clean up the fake uplink:

    ./delete_fake_uplink.sh


Devstack Integration
--------------------

DevStack executes DevMido's scripts to set up the MidoNet environment.  To run
DevStack, refer to:

    https://github.com/stackforge/networking-midonet/blob/master/devstack/README.rst

