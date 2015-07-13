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
    LocalPortActive |        v                        |
                    |    PacketWorkFlow----+          |
                    |                      |          |
                    |                      |          |
              +--------------------------------------------------------+
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

On startup:

During midolman startup, "metadata" port is set up.  Metadata Proxy,
which is a special-purpse HTTP proxy, is launched as a part of the
midolman process.  It listens on 169.254.169.254:9697.

On packet-level:

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
   MetadataServiceWorkflow also responds to ARP requests.

On HTTP-level:

Metadata Proxy accepts the request.  It queries InstanceInfoMap using
the peer's IP address.  Note: The IP address is the result of
packet rewrite in step #3.
It appends some extra headers to the request and forwards it to
Nova Metadata Api.

### References

AWS documentation:

* http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
