import os
import ConfigParser


conf_file = os.getenv('MDTS_CONF_FILE', 'mdts.conf')

conf = ConfigParser.ConfigParser()
conf.read(conf_file)

def is_vxlan_enabled():
    """Returns boolean to indicate if vxlan tunnels are enabled"""
    return conf.getboolean('functional_tests', 'vxlan')
