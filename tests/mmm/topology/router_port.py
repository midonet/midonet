from topology.resource_base import ResourceBase

class RouterPort(ResourceBase):

    def __init__(self,attrs):
        self.portAddress = attrs['portAddress']
        self.networkAddress = attrs['networkAddress']
        self.networkLength = attrs['networkLength']
        self.rules = attrs.get('rules')
        self.bgp = attrs.get('bgp')
