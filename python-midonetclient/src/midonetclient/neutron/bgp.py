# Copyright (c) 2016 Midokura SARL, All Rights Reserved.
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

from midonetclient.neutron import media_type
from midonetclient.neutron import url_provider


LOG = logging.getLogger(__name__)


class BgpUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """BGP URL provider mixin

    This mixin provides URLs for BGP resources
    """

    def bgp_speaker_url(self, bgp_speaker_id):
        return self.neutron_template_url("bgp_speaker_template",
                                         bgp_speaker_id)

    def bgp_speakers_url(self):
        return self.neutron_resource_url("bgp_speakers")

    def bgp_peer_url(self, bgp_peer_id):
        return self.neutron_template_url("bgp_peer_template",
                                         bgp_peer_id)

    def bgp_peers_url(self):
        return self.neutron_resource_url("bgp_peers")


class BgpClientMixin(BgpUrlProviderMixin):
    """BGP operation mixin

    Mixin that defines all the Neutron BGP operations in MidoNet API.
    """

    def update_bgp_speaker(self, bgp_speaker_id, bgp_speaker):
        LOG.info("update_bgp_speaker %r", bgp_speaker)
        return self.client.put(self.bgp_speaker_url(bgp_speaker_id),
                               media_type.BGP_SPEAKER,
                               bgp_speaker)

    def create_bgp_peer(self, bgp_peer):
        LOG.info("create_bpg_peer %r", bgp_peer)
        return self.client.post(self.bgp_peers_url(),
                                media_type.BGP_PEER,
                                body=bgp_peer)

    def delete_bgp_peer(self, bgp_peer_id):
        LOG.info("bgp_peer_id %r", bgp_peer_id)
        self.client.delete(self.bgp_peer_url(bgp_peer_id))

    def update_bgp_peer(self, bgp_peer_id, bgp_peer):
        LOG.info("update_bgp_peer %r", bgp_peer)
        return self.client.put(self.bgp_peer_url(bgp_peer_id),
                               media_type.BGP_PEER,
                               bgp_peer)
