/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.core.model;

/**
 * Resource that can be managed by Resource Manager and involved into global transaction.
 * Resource是资源管理组件来负载管理的，RM负责把一个个的资源纳入到全局事务中，将数据库本地事务纳入到全局事务里去
 *
 * @author sharajava
 */
public interface Resource {

    /**
     * 获取到资源分组的ID，针对主从架构的数据源可以关联到同一个资源分组ID
     * 比如：MySQL是主从架构，主节点和从节点是两个数据源，它们就可以关联到同一个分组
     * Get the resource group id.
     * e.g. master and slave data-source should be with the same resource group id.
     *
     * @return resource group id.
     */
    String getResourceGroupId();

    /**
     * 获取单个数据源的ID（数据库连接URL可以作为数据源的ID）
     * Get the resource id.
     * e.g. url of a data-source could be the id of the db data-source resource.
     *
     * @return resource id.
     */
    String getResourceId();

    /**
     * 获取数据源的类型
     * get resource type, AT, TCC, SAGA and XA
     *
     * @return branch type
     */
    BranchType getBranchType();

}
