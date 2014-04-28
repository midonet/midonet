from midonetclient.api import MidonetApi
from topology.tenants import list_tenants
from topology.transaction import Transaction
from topology.transaction import Unlink,DeleteBridge,DeleteRouter,DeleteChain
from topology.utils import midonet_read_host_uuid
import sys
import traceback
import pdb
import webob

def delete_bridge(api,tx,bridge,parent_port=None):

    tx.append(DeleteBridge(api,bridge))

    for peer in bridge.get_peer_ports():
        if parent_port and parent_port == peer.get_id():
            tx.append(Unlink(peer))

def delete_router(api,tx,router,parent_port=None):

    tx.append(DeleteRouter(api,router))

    for port in router.get_ports():
        if port.get_type() == 'Router':
            port.delete()

    for peer in router.get_peer_ports():
        if parent_port and parent_port == peer.get_id():
            tx.append(Unlink(peer))
        else:
            device_id = peer.get_device_id()
            try:
                bridge = api.get_bridge(device_id)
                if bridge:
                    delete_bridge(api,tx,bridge,peer.get_peer_id())
            except webob.exc.HTTPNotFound:
                router = api.get_router(device_id)
                if router:
                    delete_router(api,tx,router,peer.get_peer_id())

def delete_chain(api,tx,chain):
    tx.append(DeleteChain(api,chain))

if __name__ == '__main__':
    tenantIds = map(lambda tenant: tenant.id, list_tenants())

    api = MidonetApi('http://127.0.0.1:8080/midonet-api','admin','*')

    for tenantId in tenantIds:
        # router and its children (routers and bridges)
        tx = Transaction()
        for router in api.get_routers({'tenant_id': tenantId}):
            delete_router(api,tx,router)
        tx.rollback() # commit actually

        # dangling bridge
        tx = Transaction()
        for bridge in api.get_bridges({'tenant_id': tenantId}):
            delete_bridge(api,tx,bridge)
        tx.rollback()

        tx = Transaction()
        for chain in api.get_chains({'tenant_id': tenantId}):
            delete_chain(api,tx,chain)
        tx.rollback()

    tx = Transaction()
    for zone in api.get_tunnel_zones():
        tx.append(zone)
    tx.rollback()
