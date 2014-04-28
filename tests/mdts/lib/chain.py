from mdts.lib.resource_base import ResourceBase
from mdts.lib.rule import Rule


class Chain(ResourceBase):
    """ A class representing a rule chain for functional tests.
    """

    def __init__(self, api, context, data):
        """ Initializes a rule. 

        Args:
            api: MidoNet API client object
            context: context for this topology
            data: topology data that represents this resource and below
                  in the hierarchy
            chain_id: Id of the chain this rule belongs to.
        """
        super(Chain, self).__init__(api, context, data)
        self._rules = {}

    def build(self):
        tenant_id = self._get_tenant_id()
        self._mn_resource = self._api.add_chain()
        self._mn_resource.tenant_id(tenant_id)
        self._mn_resource.name(self._get_name())
        self._mn_resource.create()

        for rule in self._data.get('rules') or []:
            self.add_rule(rule.get('rule'))

    def destroy(self):
        self.clear_rules()
        self._mn_resource.delete()

    def clear_rules(self):
        for key in self._rules:
            self._rules[key].destroy()
        self._rules = {}

    def add_rule(self, rule):
        """ Adds a given rule to this chain. """
        rule_obj = Rule(self._api, self._context, rule, self)
        rule_obj.build()
        self._rules[rule.get('id')] = rule_obj
        
    def get_rule(self, rule_id):
        """ Returns a rule in this chain with the given rule ID."""
        return self._rules[rule_id]
        
    def get_id(self):
        """ Returns the chain ID.""" 
        return self._data.get('id')