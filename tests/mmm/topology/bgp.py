from topology.resource_base import ResourceBase

class Bgp(ResourceBase):

    def __init__(self,attrs):
        self.localAS = attrs['localAS']
        self.peerAS = attrs['peerAS']
        self.peerAddr = attrs['peerAddr']
        self.adRoute = attrs['adRoute']
