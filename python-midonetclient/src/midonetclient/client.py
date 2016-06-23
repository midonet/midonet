# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import logging
from midonetclient import httpclient
from midonetclient.neutron import bgp
from midonetclient.neutron import firewall as fw
from midonetclient.neutron import firewall_logging as fw_l
from midonetclient.neutron import gateway_device as gd
from midonetclient.neutron import l2gw
from midonetclient.neutron import l3
from midonetclient.neutron import loadbalancer as lb
from midonetclient.neutron import network as net
from midonetclient.neutron import securitygroup as sg
from midonetclient.neutron import taas
from midonetclient.neutron import vpn

LOG = logging.getLogger(__name__)


class MidonetClient(net.NetworkClientMixin,
                    bgp.BgpClientMixin,
                    gd.GatewayDeviceClientMixin,
                    l2gw.L2GwClientMixin,
                    l3.L3ClientMixin,
                    sg.SecurityGroupClientMixin,
                    lb.LoadBalancerClientMixin,
                    fw.FirewallClientMixin,
                    fw_l.FirewallLoggingClientMixin,
                    taas.TaasClientMixin,
                    vpn.VPNClientMixin):
    """Main MidoNet client class

    The main class for MidoNet client.  Instantiate this class to make API
    calls to MidoNet API.
    """

    def __init__(self, base_uri, username, password, project_id=None):
        self.base_uri = base_uri
        self.client = httpclient.HttpClient(base_uri, username, password,
                                            project_id=project_id)
        super(MidonetClient, self).__init__()
