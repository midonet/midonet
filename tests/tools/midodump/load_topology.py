# -*- coding: utf-8 -*-
#!/usr/bin/env python

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
