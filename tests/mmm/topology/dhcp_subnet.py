from topology.resource_base import ResourceBase

class DhcpSubnet(ResourceBase):

    def __init__(self,attrs):
        self.defaultGateway = attrs['defaultGateway']
        self.subnetPrefix = attrs['subnetPrefix']
        self.subnetLength = attrs['subnetLength']
        self.opt121Routes = attrs.get('opt121Routes')
        self.dhcpHosts = attrs.get('dhcpHosts')
