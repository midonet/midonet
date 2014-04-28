from topology.resource_base import ResourceBase

class Chain(ResourceBase):

    def __init__(self,attrs):
        self.name = attrs['name']
        self.tenantId = attrs['tenantId']

    def add(self,api,tx):
        chain = api.add_chain()\
            .tenant_id(self.tenantId)\
            .name(self.name)\
            .create()
        tx.append(chain)
        self.resource = chain

    def get_id(self):
        return self.resource.get_id()
