from topology.resource_base import ResourceBase

class AdRoute(ResourceBase):

    def __init__(self,attrs):
        self.nwPrefix = attrs['nwPrefix']
        self.prefixLength = attrs['prefixLength']
