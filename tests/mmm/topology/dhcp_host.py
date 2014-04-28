from topology.resource_base import ResourceBase

class DhcpHost(ResourceBase):

    def __init__(self,attrs):
        self.name = attrs['name']
        self.ipAddr = attrs['ipAddr']
        self.macAddr = attrs['macAddr']
