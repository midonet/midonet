from topology.resource_base import ResourceBase

class HostInterface(ResourceBase):

    def __init__(self,attrs):
        self.hostId = attrs['hostId']
        self.name = attrs['name']
