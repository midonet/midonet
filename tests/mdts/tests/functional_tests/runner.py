#!/usr/bin/env python
import nose
from mdts.lib.nose_plugin import Mdts

if __name__ == '__main__':
    nose.main(addplugins=[Mdts()])
