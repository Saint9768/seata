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
package io.seata.saga.engine.store;

import io.seata.saga.statelang.domain.StateMachine;

/**
 * State language definition store
 *
 * @author lorne.cl
 */
public interface StateLangStore {

    /**
     * Query the state machine definition by id
     * 根据状态机ID获取状态机
     *
     * @param stateMachineId
     * @return
     */
    StateMachine getStateMachineById(String stateMachineId);

    /**
     * Get the latest version of the state machine by state machine name
     * 根据状态机名称和租户ID获取最新版本的状态机
     *
     * @param stateMachineName
     * @param tenantId
     * @return
     */
    StateMachine getLastVersionStateMachine(String stateMachineName, String tenantId);

    /**
     * Storage state machine definition
     * 存储一个状态机
     *
     * @param stateMachine
     * @return
     */
    boolean storeStateMachine(StateMachine stateMachine);
}