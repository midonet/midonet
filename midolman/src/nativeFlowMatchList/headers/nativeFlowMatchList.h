/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef _NATIVE_FLOW_MATCH_LIST_H_
#define _NATIVE_FLOW_MATCH_LIST_H_

#include <string>
#include <vector>

using FlowMatch = std::string;

/*
 * Out of band flow match list.
 *
 * This is a container for all the flows that are present on the datapath
 * during agent boot. These flows will be queried by the agent process
 * in batches so they can be expired gradually.
 *
 */
class FlowMatchList {
  public:
    FlowMatchList();
    void push_flow_match(FlowMatch match);
    FlowMatch pop_flow_match();
    int size();
  private:
    std::vector<FlowMatch> m_list;
};

#endif /* _NATIVE_FLOW_MATCH_LIST_H_ */
