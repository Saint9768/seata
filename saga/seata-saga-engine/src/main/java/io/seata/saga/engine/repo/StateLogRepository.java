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
package io.seata.saga.engine.repo;

import java.util.List;

import io.seata.saga.statelang.domain.StateInstance;
import io.seata.saga.statelang.domain.StateMachineInstance;

/**
 * State Log Repository
 *
 * @author lorne.cl
 */
public interface StateLogRepository {

    /**
     * Get state machine instance
     * 根据状态机实例ID，获取一个具体的状态机实例
     *
     * @param stateMachineInstanceId
     * @return
     */
    StateMachineInstance getStateMachineInstance(String stateMachineInstanceId);

    /**
     * Get state machine instance by businessKey
     * 根据业务key获取一个具体的状态机实例
     *
     * @param businessKey
     * @param tenantId
     * @return
     */
    StateMachineInstance getStateMachineInstanceByBusinessKey(String businessKey, String tenantId);

    /**
     * Query the list of state machine instances by parent id
     * 根据parentId去获取所有的子状态机实例
     *
     * @param parentId
     * @return
     */
    List<StateMachineInstance> queryStateMachineInstanceByParentId(String parentId);

    /**
     * Get state instance
     * 根据状态实例ID获取状态实例
     *
     * @param stateInstanceId
     * @param machineInstId
     * @return
     */
    StateInstance getStateInstance(String stateInstanceId, String machineInstId);

    /**
     * Get a list of state instances by state machine instance id
     * 根据状态机实例ID获取所有的状态实例
     *
     * @param stateMachineInstanceId
     * @return
     */
    List<StateInstance> queryStateInstanceListByMachineInstanceId(String stateMachineInstanceId);
}