/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.util.functors;

import java.util.List;

/**
 * Applies a functor on all nodes of a tree,
 * from the bottom leaves up to the root,
 * accumulating the results in the 'acc' list.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/5/12
 */
public class TreeNodeFunctors {

    public static <T, Node> List<T> recursiveBottomUpFold(TreeNode<Node> root,
                                                       Functor<Node, T> func,
                                                       List<T> acc)
        throws Exception {

        List<TreeNode<Node>> children = root.getChildren();

        for (TreeNode<Node> child : children) {
            recursiveBottomUpFold(child, func, acc);
        }

        T processedRoot = func.apply(root.getValue());
        if (processedRoot != null) {
            acc.add(processedRoot);
        }

        return acc;
    }

}
