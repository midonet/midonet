import re

def midonet_read_host_uuid(path):

    with open(path,"r") as f:
        for line in f.readlines():
            m = re.match("^host_uuid=(.*)$",line)
            if m:
                return m.group(1)
    return None
