from topology.resource_base import ResourceBase

class Rule(ResourceBase):

    def __init__(self,attrs):
        pass

class RuleMasq(Rule):

    def __init__(self,attrs):
        super(RuleMasq, self).__init__(attrs)
        self.snat_ip = attrs['snat_ip']

class RuleFloatIP(Rule):

    def __init__(self,attrs):
        super(RuleFloatIP, self).__init__(attrs)

        self.fixed_ip = attrs['fixed_ip']
        self.float_ip = attrs['float_ip']

class RuleTransProxy(Rule):

    def __init__(self,attrs):
        super(RuleTransProxy, self).__init__(attrs)

        self.fixed_ip = attrs['fixed_ip']
        self.float_ip = attrs['float_ip']


