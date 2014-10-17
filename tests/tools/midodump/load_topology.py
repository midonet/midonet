# -*- coding: utf-8 -*-
#!/usr/bin/env python

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
load_topology.py - The script constructs the virtual topology for MidoNet CP

Copyright 2013 Midokura Japan K.K.

Usage:
  $ python load_topology.py default_topology.yaml
"""

import logging
import sys
import traceback

from mdts.lib.virtual_topology_manager import VirtualTopologyManager

LOG = logging.getLogger(__name__)

if __name__ == '__main__':
    try:
        filename = sys.argv[1]
        if not filename:
            print 'filename of the topology in the YAML format is requied'
            exit()
        vtm = VirtualTopologyManager(filename)
        LOG.debug('Building the virtual topology for CP')
        vtm.build()
        print 'Succeded to construct the topology.'
    except Exception as e:
        tb = traceback.format_exc()
        LOG.error(e)
        print tb
