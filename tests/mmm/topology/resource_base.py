class ResourceBase(object):

    def __init(self):
        self._resource = None

    @property
    def resource(self):
        return self._resource
        
    @resource.setter
    def resource(self,value):
        self._resource = value
