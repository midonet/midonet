
## virtualized networking for public and private clouds

**MidoNet** is a network platform that can be used to build arbitrary network
topologies on top of an existing IP network without having to modify it.

You do not need special hardware for using MidoNet, it is all based on software.

![MidoNet in Neutron](http://i2.wp.com/blog.midonet.org/wp-content/uploads/2014/12/MidoNetNeutronOverlay.png "MidoNet in Neutron")

Network virtualization technology is used at cloud service providers and in private clouds
to encourage more dynamic and faster network usage and thus allowing faster time to market
for the applications of your customers running in containers and/or virtual machines.

![Tenant Router Model](http://i0.wp.com/blog.midonet.org/wp-content/uploads/2014/12/MNProviderRouter.jpg "Tenant Router Model")

MidoNet allows you to directly connect to existing physical networks using either special switch hardware
containing a L2 hardware gateway software (VTEP), or a L2 software gateway in MidoNet, which also support VLAN ids.

![MidoNet L2 Gateway](http://i0.wp.com/blog.midonet.org/wp-content/uploads/2014/12/Blog-L2-Gateways-2.png "MidoNet L2 Gateway")

The most interesting side effect of using a scalable, distributed system is the economical savings in electricity and air conditioning.

Instead of sending all traffic through large proprietary boxes, the decisions about the traffic of virtual machines are computed on the hypervisor
where the vm is located and traffic that should go to the internet moves through commodity intel servers acting as L3 gateways and L4 load balancers.

This way a cloud operator can organically scale out compute nodes and gateways while at the same time being respectful to nature and the environment, 
avoiding wasted energy and unnecessary cooling of large, often underutilized network appliances.

## About MidoNet

Our software supports virtual L2 switches, virtual L3 routing, distributed, stateful
source NAT and distributed stateful L4 TCP load balancing.

The core of the software is a java-based flow simulator agent that gets installed
on each hypervisor and on gateway nodes, responsible for north-south traffic.

While the agent uses the datapath from openvswitch, all other openvswitch
userland components are replaced and obsoleted by using MidoNet.

The traffic between virtual machines is encapsulated in tunnels (GRE or Vxlan) which means
the existing network does not have to be changed to use our network virtualization technology.

## Quick Installation

For a quick installation using a simple downloadable script, refer to this website: http://www.midonet.org/#quickstart

If you want to see how everything works together, this website will show you
how to build a simple dev environment on your machine with MidoNet and OpenStack: http://wiki.midonet.org/MidoNet-allinone

## Find out more

You can find out more about the MidoNet community at the following websites:

* the main community project website: http://www.midonet.org/
* a blog of our developers: http://blog.midonet.org/

If you are completely new to Neutron and network virtualization, this blog article is a good start:
http://blog.midonet.org/introduction-mns-overlay-network-models-part-1-provider-router/

Its a series of articles, they are all recommended for reading to learn more about the architecture of our software.

* here you find all the technical documentation about MidoNet and OpenStack: http://docs.midonet.org/
* learn more about MidoNet from a developers point of view by reading https://github.com/midonet/midonet/blob/master/DEVELOPMENT.md
* if you want to contribute, there is an excellent starter guide: http://wiki.midonet.org/HowToContribute?action=show&redirect=How+to+contribute

## Get in touch with us

Our developers are always happy to talk to new faces.

Visit our slack channel and take part in the mailing list discussions about the future of the solution and new features.

Your input is appreciated and welcome, we are very glad to learn about new innovations and ideas from our community!

* http://slack.midonet.org/

* http://lists.midonet.org/listinfo/midonet-dev

