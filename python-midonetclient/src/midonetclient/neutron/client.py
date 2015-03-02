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

from midonetclient import client


class MidonetClient(client.MidonetClient):
    """Main MidoNet client class

    The main class for MidoNet client.  Instantiate this class to make API
    calls to MidoNet API.

    This class is deprecated.  Use the class with the same name in
    'midonetclient.client' module.  This class exists only to not break
    backward compatibility.
    """

    def __init__(self, base_uri, username, password, project_id=None):
        super(MidonetClient, self).__init__(base_uri, username, password,
                                            project_id=project_id)
