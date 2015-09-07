## Summary

What follow is a step-by-step guide to getting a midolman & midonet-cluster
up and running on a development environment with no OpenStack dependencies.

It will cover the following steps, assuming a debian/ubuntu system:

 * Install dependencies.
 * Install midolman, midonet-cluster and python-midonetclient.
 * Use midonet-cli to create a trivial virtual network topology.
 * Hook two network namespaces to ports in the virtual network.
 * Pass network traffic through the virtal network.

The guide assume that midonet packages have been built following the
instructions in the project README.

## Install dependencies

Because we shall be installing locally built packages, we need to
install run time dependencies for midolman and midonet-cluster manually.
We assume build dependencies are already installed and packages have
been built.

If not then refer to the Build dependencies and Building the Project guide
documentation in [ DEVELOPMENT.md ][development] before continuing.

[development]: ../DEVELOPMENT.md

    $ sudo apt-get install zookeeper haproxy quagga bridge-utils zookeeperd
    $ sudo /etc/init.d/zookeeper start
    $ sudo /etc/init.d/tomcat7 start

Let's also install the dependencies for python-midonetclient:

    $ sudo apt-get install python-webob python-eventlet python-httplib2

## Install midonet-packages

python-midonetclient comes from its own code repository:

    $ sudo dpkg -i python-midonetclient_5.0.0_all.deb

And now, midolman and midonet-cluster:

    $ sudo dpkg -i midolman/build/packages/*.deb cluster/midonet-cluster/build/packages/*.deb

## Auth in midonet-cluster

This environment won't have keystone, and we don't need any
authentication.  This is the default in midonet-cluster, so you don't
need to do anything.  To enable keystone authentication, refer to the
MidoNet documentation at <http://docs.midonet.org>

    $ sudo service midonet-cluster restart

Now we can set up midonet-cli and check that the API responds:

    $ cat <<EOF >> ~/.midonetrc
    > [cli]
    > api_url = http://127.0.0.1:8181/midonet-api
    > username = none
    > password = pass
    > tenant = midonet
    > project_id = midonet
    > EOF
    $ midonet-cli
    midonet> router list
    midonet>

## Running midolman and creating a virtual network

Once we start midolman:

    $ sudo start midolman

It should show up in CLI's list of hosts:

    midonet> host list
    host host0 name monk alive true

And we can create a virtual network. Let's add a bridge with two ports:

    midonet> bridge create name demo
    bridge0
    midonet> bridge bridge0 add port
    bridge0:port0
    midonet> bridge bridge0 add port
    bridge0:port1
    midonet> bridge list
    bridge bridge0 name demo state up
    midonet> bridge bridge0 list port
    port port1 device bridge0 state up
    port port0 device bridge0 state up
    midonet>

## Connecting two network namespaces to the virtual network

First, let's create two symmetrical network namespaces, with ip addresses
in the same range and names "left" and "right":

    ip netns add left
    ip link add name leftdp type veth peer name leftns
    ip link set leftdp up
    ip link set leftns netns left
    ip netns exec left ip link set leftns up
    ip netns exec left ip address add 10.25.25.1/24 dev leftns
    ip netns exec left ip link set dev lo up


    ip netns add right
    ip link add name rightdp type veth peer name rightns
    ip link set rightdp up
    ip link set rightns netns right
    ip netns exec right ip link set rightns up
    ip netns exec right ip address add 10.25.25.2/24 dev rightns
    ip netns exec right ip link set dev lo up

Now we have two namespaces. On the host side, they are each hooked to
two network interfaces names "leftdp" and "rightdp". They have those names
because those are the interfaces we'll hook to the datapath.

    $ ip a
    6: leftdp: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
        link/ether ae:1b:64:d8:25:c6 brd ff:ff:ff:ff:ff:ff
        inet6 fe80::ac1b:64ff:fed8:25c6/64 scope link
           valid_lft forever preferred_lft forever
    8: rightdp: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
        link/ether d2:ad:cc:c8:e7:b9 brd ff:ff:ff:ff:ff:ff
        inet6 fe80::d0ad:ccff:fec8:e7b9/64 scope link
           valid_lft forever preferred_lft forever

Now we can connect them to the virtual network:

    midonet> host host0 list interface
    iface lo host_id host0 status 3 addresses [u'127.0.0.1', u'0:0:0:0:0:0:0:1'] mac 00:00:00:00:00:00 mtu 65536 type Virtual endpoint LOCALHOST
    iface eth1 host_id host0 status 3 addresses [u'192.168.56.101', u'fe80:0:0:0:a00:27ff:fe08:8dde'] mac 08:00:27:08:8d:de mtu 1500 type Physical endpoint PHYSICAL
    iface eth0 host_id host0 status 3 addresses [u'10.0.2.15', u'fe80:0:0:0:a00:27ff:fecc:86fd'] mac 08:00:27:cc:86:fd mtu 1500 type Physical endpoint PHYSICAL
    iface leftdp host_id host0 status 3 addresses [u'fe80:0:0:0:ac1b:64ff:fed8:25c6'] mac ae:1b:64:d8:25:c6 mtu 1500 type Virtual endpoint UNKNOWN
    iface rightdp host_id host0 status 3 addresses [u'fe80:0:0:0:d0ad:ccff:fec8:e7b9'] mac d2:ad:cc:c8:e7:b9 mtu 1500 type Virtual endpoint UNKNOWN


Hosts need to be in a tunnel zone before they can bind interfaces to virtual ports.

    midonet> tunnel-zone create name default type gre
    tzone0
    midonet> tunnel-zone tzone0 add member host host0 address 127.0.0.1
    zone tzone0 host host0 address 127.0.0.1

Now we can bind our ports:

    midonet> host host0 add binding interface leftdp port bridge0:port0
    host host0 interface leftdp port bridge0:port0
    midonet> host host0 add binding interface rightdp port bridge0:port1
    host host0 interface rightdp port bridge0:port1

We can look at midolman.log and see that it discovered the new port bindings and activated the ports:

    16:29:04.933 INFO  [midolman-akka.actor.default-dispatcher-2] datapath-control -  Port 4/leftdp/272697ad-871e-48fa-be2d-05f46531e7ea became active
    16:29:15.556 INFO  [midolman-akka.actor.default-dispatcher-2] datapath-control -  Port 5/rightdp/3f5e3412-5c54-466f-a094-5728415d9cbd became active

Which means that, our two namespaces are now connected to the same virtual bridge, and can talk to each other:

    [monk] midonet$ sudo ip netns exec right ping 10.25.25.1
    PING 10.25.25.1 (10.25.25.1) 56(84) bytes of data.
    64 bytes from 10.25.25.1: icmp_seq=1 ttl=64 time=3.12 ms
    64 bytes from 10.25.25.1: icmp_seq=2 ttl=64 time=2.91 ms
    64 bytes from 10.25.25.1: icmp_seq=3 ttl=64 time=0.215 ms
    64 bytes from 10.25.25.1: icmp_seq=4 ttl=64 time=0.093 ms
    64 bytes from 10.25.25.1: icmp_seq=5 ttl=64 time=0.087 ms
    64 bytes from 10.25.25.1: icmp_seq=6 ttl=64 time=0.094 ms
