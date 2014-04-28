from midonetclient.resource_base import ResourceBase
import sys
import traceback

class Unlink:

    def __init__(self,port):
        self.port = port

    def unlink(self):
        self.port.unlink()

class Delete:

    def __init__(self):
        pass

class DeleteBridge(Delete):

    def __init__(self,api,bridge):
        self.api = api
        self.bridge = bridge

    def delete(self):
        self.api.delete_bridge(self.bridge.get_id())

class DeleteRouter(Delete):

    def __init__(self,api,router):
        self.api = api
        self.router = router

    def delete(self):
        self.api.delete_router(self.router.get_id())

class DeleteChain(Delete):

    def __init__(self,api,chain):
        self.api = api
        self.chain = chain

    def delete(self):
        self.api.delete_chain(self.chain.get_id())

class Transaction:

    def __init__(self):
        self.log = []

    def append(self,obj):
        self.log.append(obj)

    def rollback(self):
        for obj in reversed(self.log):
            if isinstance(obj,Unlink):
                try:
                    obj.unlink()
                except:
                    traceback.print_exc()
            elif isinstance(obj,Delete):
                try:
                    obj.delete()
                except:
                    traceback.print_exc()
            elif isinstance(obj,ResourceBase):
                try:
                    obj.delete()
                except:
                    traceback.print_exc()
