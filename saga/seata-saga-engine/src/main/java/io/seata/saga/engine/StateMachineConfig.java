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
package io.seata.saga.engine;

import java.util.concurrent.ThreadPoolExecutor;

import io.seata.saga.engine.evaluation.EvaluatorFactoryManager;
import io.seata.saga.engine.expression.ExpressionFactoryManager;
import io.seata.saga.engine.invoker.ServiceInvokerManager;
import io.seata.saga.engine.repo.StateLogRepository;
import io.seata.saga.engine.repo.StateMachineRepository;
import io.seata.saga.engine.sequence.SeqGenerator;
import io.seata.saga.engine.store.StateLangStore;
import io.seata.saga.engine.store.StateLogStore;
import io.seata.saga.engine.strategy.StatusDecisionStrategy;
import io.seata.saga.proctrl.eventing.impl.ProcessCtrlEventPublisher;
import org.springframework.context.ApplicationContext;

import javax.script.ScriptEngineManager;

/**
 * StateMachineConfig
 *
 * @author lorne.cl
 */
public interface StateMachineConfig {

    /**
     * Gets state log store.
     * 获取状态日志数据的仓储组件（获取状态机实例、状态实例的数据）
     *
     * @return the StateLogRepository
     */
    StateLogRepository getStateLogRepository();

    /**
     * Gets get state log store.
     * 获取状态日志存储组件（记录状态机实例事件、状态实例事件数据，获取状态机实例、状态实例的数据）
     * StateLogStore 比 StateLogRepository 功能更多
     *
     * @return the get StateLogStore
     */
    StateLogStore getStateLogStore();

    /**
     * Gets get state language definition store.
     * 获取状态定义存储组件（对状态机定义的数据进行存取）
     *
     * @return the get StateLangStore
     */
    StateLangStore getStateLangStore();

    /**
     * Gets get expression factory manager.
     * 获取表达式工厂管理组件（解析状态机中的表达式）
     *
     * @return the get expression factory manager
     */
    ExpressionFactoryManager getExpressionFactoryManager();

    /**
     * Gets get evaluator factory manager.
     * 获取表达式计算工厂管理器
     *
     * @return the get evaluator factory manager
     */
    EvaluatorFactoryManager getEvaluatorFactoryManager();

    /**
     * Gets get charset.
     *
     * @return the get charset
     */
    String getCharset();

    /**
     * Gets get default tenant id.
     *
     * @return the default tenant id
     */
    String getDefaultTenantId();

    /**
     * Gets get state machine repository.
     * 获取状态机仓储组件
     *
     * @return the get state machine repository
     */
    StateMachineRepository getStateMachineRepository();

    /**
     * Gets get status decision strategy.
     *
     * @return the get status decision strategy
     */
    StatusDecisionStrategy getStatusDecisionStrategy();

    /**
     * Gets get seq generator.
     *
     * @return the get seq generator
     */
    SeqGenerator getSeqGenerator();

    /**
     * Gets get process ctrl event publisher.
     * 获取流程控制事件发布组件
     *
     * @return the get process ctrl event publisher
     */
    ProcessCtrlEventPublisher getProcessCtrlEventPublisher();

    /**
     * Gets get async process ctrl event publisher.
     * 获取异步化的流程控制事件发布组件
     *
     * @return the get async process ctrl event publisher
     */
    ProcessCtrlEventPublisher getAsyncProcessCtrlEventPublisher();

    /**
     * Gets get application context.
     * 获取应用容器
     *
     * @return the get application context
     */
    ApplicationContext getApplicationContext();

    /**
     * Gets get thread pool executor.
     * 获取线程池
     *
     * @return the get thread pool executor
     */
    ThreadPoolExecutor getThreadPoolExecutor();

    /**
     * Is enable async boolean.
     *
     * @return the boolean
     */
    boolean isEnableAsync();

    /**
     * get ServiceInvokerManager
     * 获取服务调用管理器
     *
     * @return
     */
    ServiceInvokerManager getServiceInvokerManager();

    /**
     * get trans operation timeout
     * 事务超时时间
     *
     * @return
     */
    int getTransOperationTimeout();

    /**
     * get service invoke timeout
     * 服务调用超时时间
     *
     * @return
     */
    int getServiceInvokeTimeout();

    /**
     * get ScriptEngineManager
     *
     * @return
     */
    ScriptEngineManager getScriptEngineManager();
}