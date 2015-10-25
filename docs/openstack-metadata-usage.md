## OpenStack Instance Metadata Service Integration

MidoNet optionally provides Instance Metadata Proxy for OpenStack Integration.
It basically replaces the similar proxy implementation provided by Neutron.
This documentation describes its usage.

### Configuration

There are a few relevant configuration knobs you can tweak with mn-conf.
You need to restart midolman to reload these configurations.

|mn-conf key                               |default value        |
|:-----------------------------------------|:--------------------|
|agent.openstack.metadata.nova_metadata_url|http://localhost:8775|
|agent.openstack.metadata.shared_secret    |(empty string)       |
|agent.openstack.metadata.enabled          |false                |

### Prerequisites

This feature requires the v2 architecture. (ZOOM)

### Limitations and known problems

#### Hypervisor network stack requirement

If enabled, this feature uses 169.254/64 link-local addresses on
the hypervisor.  midolman creates a pseudo network interface named
"midonet", and assigns the metadata service address (169.254.169.254)
on it.  Also, it listens on TCP 169.254.169.254:9697 for incoming
metadata requests.

<pre>
      guest
       | 169.254.x.x (NAT'ed)
       |
     +-+------+-------------------------+ 169.254.0.0/16
              |
              |      (virtual network provided by midolman)
   - - - - - -|- - - - - - - - - - - - - - - - - - - - - - - - - -
              |      (hypervisor network)
              |
          "metadata" interface
              | 169.254.169.254
              | port 9697
          midolman internal http server
</pre>

Please make sure that these addresses and ports are not used for
other purposes on the hypervisor.

Also, please ensure that the above configuration is allowed by
filtering rules.  For example,
<pre>
    # iptables -I INPUT 1 -i metadata -j ACCEPT
</pre>

#### Guest OS network stack requirement

Unless either of the following is true, you might need to tweak
guest OSes so that they can communicate with the metadata service
address. (169.254.169.254)

* The guest OS has embedded knowledge about IPv4 link-local addresses.
  Many of modern operating systems, including Ubuntu 14.04 and OS X 10.10.5,
  fall into this category.  In this case, no special configuration
  is necessary.

* The tenant network has the default gateway.
  In this case, the address can be forwarded to the gateway.  This
  implementation intercepts requests even if their L2 destination is
  the gateway's one.

Typically adding a link-local route covering 169.254.169.254 is enough.
For example,
<pre>
    # route add -net 169.254.0.0 netmask 255.255.0.0 dev eth0
</pre>

### How to configure

#### MidoNet

To use MidoNet Metadata Proxy, you need to enable it via mn-conf.
These are deployment-global settings.  They will take effect next
time each midolman is (re-)started.

|mn-conf key                               |appropriate value    |
|:-----------------------------------------|:--------------------|
|agent.openstack.metadata.nova_metadata_url|http://${nova_metadata_host}:${nova_metadata_port} to specify the address on which Nova Metadata Api is available.  The port number is typically 8775.|
|agent.openstack.metadata.shared_secret    |A secret string shared with Nova Metadata Api.  The corresponding configuration on Nova side is neutron.metadata_proxy_shared_secret.|
|agent.openstack.metadata.enabled          |true                 |

#### Nova Metadata Api

Nova Metadata Api configuration is same as the case of
Neutron Metadata Proxy.  It should accept HTTP requests from
every hosts on which midolman serves Metadata-using VMs.

|section|key                         |value                  |
|:------|:---------------------------|:----------------------|
|neutron|service_metadata_proxy      |True                   |
|neutron|metadata_proxy_shared_secret|A secret string shared with MidoNet Metadata Proxy.  The corresponding configuration on MidoNet side is agent.openstack.metadata.shared_secret.|

See OpenStack Admin Guide for details:

* http://docs.openstack.org/admin-guide-cloud/compute-networking-nova.html#metadata-service
* http://docs.openstack.org/admin-guide-cloud/networking_config-identity.html#configure-metadata

### Migration from Neutron metadata proxy

This section describes a procedure to migrate existing deployments
from Neutron metadata proxy to MidoNet metadata proxy.

This section assumes the following configuration, which is typical
for OpenStack deployments with MidoNet:

```ini
[DEFAULT]
enable_isolated_metadata = True
dhcp_driver = midonet.neutron.agent.midonet_driver.DhcpNoOpDriver
interface_driver = neutron.agent.linux.interface.MidonetInterfaceDriver
use_namespaces = True
```

Also, the deployment needs to meet the prerequisites mentioned
in the "Prerequisites" section above, of course.

#### Migration procedure

1. Set agent configurations via mn-conf.  They are deployment-global.

    |mn-conf key                               |appropriate value|
    |:-----------------------------------------|-----------------|
    |agent.openstack.metadata.nova_metadata_url|"http://${nova_metadata_ip}:${nova_metadata_port}" where nova_metadata_ip and nova_metadata_port are the corresponding neutron-metadata-agent configuration.|
    |agent.openstack.metadata.shared_secret    |same as "metadata_proxy_shared_secret" neutron-metadata-agent configuration.|
    |agent.openstack.metadata.enabled          |true             |

2. Restart midolman on each hypervisors.  After the restart, VMs on
  the hypervisor will be served by MidoNet metadata proxy.

#### Optional clean-ups after migration

After migrating all hypervisors, you might want to stop the relevant
Neutron agents, namely neutron-dhcp-agent and neutron-metadata-agent,
as they are not necessary anymore.  Also, you might or might not want
to clean up dhcp ports and namespaces which were used by
neutron-dhcp-agent.

##### dhcp namespaces

neutron-dhcp-agent creates Linux network namespaces for each networks
it handles, with names "qdhcp-\<network UUID\>".

<pre>
ubu7% ip netns
qdhcp-0b2f8092-c743-4d45-ae15-050be29a69f7
ubu7%
</pre>

While MidoNet metadata proxy does not use them, it's basically harmless
to leave these namespaces unused.  So we recommend not to bother to
clean them up.

##### dhcp ports

neutron-dhcp-agent creates special Neutron ports to communicate
with the tenant network.  They are called dhcp ports.
MidoNet metadata proxy does not use them.
Their device_owner attribute is "network:dhcp" and their device_id
attribute is typically "dhcp\<per-host dhcp UUID\>-\<network UUID\>".

<pre>
ubu7% neutron port-list -c id -c device_owner -c device_id
+--------------------------------------+--------------------------+-------------------------------------------------------------------------------+
| id                                   | device_owner             | device_id                                                                     |
+--------------------------------------+--------------------------+-------------------------------------------------------------------------------+
| ccfa3e0d-128d-4398-8a09-6c5eb7910bef | network:router_gateway   | 00fb5964-6d1a-405b-9323-2b2a11767123                                          |
| e5bb14a5-61ac-4cee-bade-0f7387347a57 | network:dhcp             | dhcp095f38d1-980c-5aba-abfc-1746cd3f9a24-0b2f8092-c743-4d45-ae15-050be29a69f7 |
| edc27407-e762-4470-bf02-9e3d39fa0fd4 | network:router_interface | 00fb5964-6d1a-405b-9323-2b2a11767123                                          |
+--------------------------------------+--------------------------+-------------------------------------------------------------------------------+
ubu7%
</pre>

Removing a dhcp port have a few effects.

1. It frees up a tenant IP address
2. MidoNet dhcp server stops propagating the relevant routes to VMs

You can remove a dhcp port in the same way as ordinary Neutron ports.

<pre>
ubu7% neutron port-list -c id -c device_owner -c device_id
+--------------------------------------+--------------------------+-------------------------------------------------------------------------------+
| id                                   | device_owner             | device_id                                                                     |
+--------------------------------------+--------------------------+-------------------------------------------------------------------------------+
| ccfa3e0d-128d-4398-8a09-6c5eb7910bef | network:router_gateway   | 00fb5964-6d1a-405b-9323-2b2a11767123                                          |
| e5bb14a5-61ac-4cee-bade-0f7387347a57 | network:dhcp             | dhcp095f38d1-980c-5aba-abfc-1746cd3f9a24-0b2f8092-c743-4d45-ae15-050be29a69f7 |
| edc27407-e762-4470-bf02-9e3d39fa0fd4 | network:router_interface | 00fb5964-6d1a-405b-9323-2b2a11767123                                          |
+--------------------------------------+--------------------------+-------------------------------------------------------------------------------+
ubu7% neutron port-delete e5bb14a5-61ac-4cee-bade-0f7387347a57
Deleted port: e5bb14a5-61ac-4cee-bade-0f7387347a57
ubu7% neutron port-list -c id -c device_owner -c device_id
+--------------------------------------+--------------------------+--------------------------------------+
| id                                   | device_owner             | device_id                            |
+--------------------------------------+--------------------------+--------------------------------------+
| ccfa3e0d-128d-4398-8a09-6c5eb7910bef | network:router_gateway   | 00fb5964-6d1a-405b-9323-2b2a11767123 |
| edc27407-e762-4470-bf02-9e3d39fa0fd4 | network:router_interface | 00fb5964-6d1a-405b-9323-2b2a11767123 |
+--------------------------------------+--------------------------+--------------------------------------+
ubu7%
</pre>

You can use tools/metadata/remove_dhcp_ports.sh script to remove
all dhcp ports.
