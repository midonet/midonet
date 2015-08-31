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

### Limitations

If enabled, this feature uses 169.254/64 link-local addresses on
the hypervisor.  Also, it listens on TCP 169.254.169.254:9697 for
incoming metadata requests.
Please make sure that these addresses and ports are not used for
other purposes on the hypervisor.

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
