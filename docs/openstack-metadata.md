## OpenStack Instance Metadata Service Integration

MidoNet optionally provides Instance Metadata Proxy for OpenStack Integration.
It basically replaces the similar proxy implementation provided by Neutron.
This documentation describes its implementation details.

### Diagram

<pre>

                    +---+ VM port
                    |        ^
                    |        |
                    |        v  packet rewrite
                    |    Datapath<----------> "metadata" port
                    |        ^                        ^ 169.254.169.254:9697
                    |        |                        |
              +--------------------------------------------------------+
              |     |        |                        |                |
              |     |        v                        |                |
              |     |    PacketWorkFlow----+          |                |
              |     |                      |          |                |
              |     |                      |          |                |
              | DatapathPortEntangler      |          |                |
              |     |                      |          |                |
              |     |LocalPortActive       |          |                |
              |     |                      |          |                |
              |  +--v-------------------+  | +--------+-------------+  |
              |  |MetadataServiceManager|  | | Metadata Proxy       |  |
              |  |                      |  | |                      |  |
              |  |                      |  | | (jetty/jersey)       |  |
              |  |                      |  | |                      |  |
              |  |                      |  | |                      |  |
              |  +---------+---------+--+  | +--+----+--------------+  |
              |            |         |     |    |    |                 |
              |            |         |     |    |    |                 |
              |            |         |     |    |    |                 |
              |            |      +--v-----v----v-+  |                 |
              |            |      |InstanceInfoMap|  |                 |
              |            |      |               |  |                 |
              |            |      |VM IP mapping  |  |                 |
              |            |      |  kept in-core |  |                 |
              |  midolman  |      +---------------+  |                 |
              |            |                         |                 |
              |            |                         |                 |
              +--------------------------------------------------------+
                           |                         |
                           v                         v
                        ZooKeeper             Nova Metadata Api

</pre>

### Workflow

#### Agent startup

During midolman startup, "metadata" port is set up.  Metadata Proxy,
which is a special-purpse HTTP proxy, is launched as a part of the
midolman process.  It listens on 169.254.169.254:9697.

#### Packet-level workflow

1. MetadataServiceManager observes LocalPortActive events.
   On VM port creation, it queries ZK about the port and VM, and
   puts the info into InstanceInfoMap.

2. VM issues Metadata request.  It's a HTTP request against
   169.254.169.254:80.

3. MetadataServiceWorkflow reacts on packet-in and installs datapath flows
   reactively.  It rewrites the proxy side of TCP port. (80 <-> 9697)
   It also rewrites the VM side of IP address, so that it can be unique
   in this hypervisor.  Note: The original IP addresses are not
   necessarily unique if they belong to different logical networks.

4. The return path is handled similarly.

#### HTTP-level workflow

When Metadata Proxy accepts a request, it queries InstanceInfoMap using
the peer's IP address.  (Note: The IP address here is the result of
packet rewrite in the step #3 above.)
It then appends some extra headers, which specifies the VM from which
the request came, to the request and forwards it to Nova Metadata Api.

#### ARP handling

MetadataServiceWorkflow replies to ARP requests from the Metadata Proxy.

It also replies to ARP requests for 169.254.169.254 from VMs.

Depending VM's networking stack and its configuration,
169.254.169.254 might be handled by the default route in the VM.
MetadataServiceWorkflow intercepts packets from VM to 169.254.169.254
ignoring L2 destination.

### References

#### AWS documentation: Instance Metadata and User Data

* http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html

#### OpenStack documentation

* http://docs.openstack.org/admin-guide-cloud/compute-networking-nova.html#metadata-service
* http://docs.openstack.org/admin-guide-cloud/networking_config-identity.html#configure-metadata
