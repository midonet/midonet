from topology.resource_base import ResourceBase

class TunnelZoneHost(ResourceBase):

    def __init__(self,attrs):
        self.hostId = attrs['hostId']
        self.ipAddress = attrs['ipAddress']
