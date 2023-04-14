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
package io.seata.saga.engine.repo.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.saga.engine.repo.StateMachineRepository;
import io.seata.saga.engine.sequence.SeqGenerator;
import io.seata.saga.engine.sequence.SpringJvmUUIDSeqGenerator;
import io.seata.saga.engine.store.StateLangStore;
import io.seata.saga.statelang.domain.DomainConstants;
import io.seata.saga.statelang.domain.StateMachine;
import io.seata.saga.statelang.parser.StateMachineParserFactory;
import io.seata.saga.statelang.parser.utils.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

/**
 * StateMachineRepository Implementation
 *
 * @author lorne.cl
 */
public class StateMachineRepositoryImpl implements StateMachineRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(StateMachineRepositoryImpl.class);
    private Map<String/** Name_Tenant **/, Item> stateMachineMapByNameAndTenant = new ConcurrentHashMap<>();
    private Map<String/** Id **/, Item> stateMachineMapById = new ConcurrentHashMap<>();
    private StateLangStore stateLangStore;
    private SeqGenerator seqGenerator = new SpringJvmUUIDSeqGenerator();
    private String charset = "UTF-8";
    private String defaultTenantId;
    private String jsonParserName = DomainConstants.DEFAULT_JSON_PARSER;

    @Override
    public StateMachine getStateMachineById(String stateMachineId) {
        Item item = CollectionUtils.computeIfAbsent(stateMachineMapById, stateMachineId,
            key -> new Item());
        if (item.getValue() == null && stateLangStore != null) {
            synchronized (item) {
                if (item.getValue() == null) {
                    StateMachine stateMachine = stateLangStore.getStateMachineById(stateMachineId);
                    if (stateMachine != null) {
                        StateMachine parsedStatMachine = StateMachineParserFactory.getStateMachineParser(jsonParserName).parse(
                            stateMachine.getContent());
                        if (parsedStatMachine == null) {
                            throw new RuntimeException(
                                "Parse State Language failed, stateMachineId:" + stateMachine.getId() + ", name:"
                                    + stateMachine.getName());
                        }
                        stateMachine.setStartState(parsedStatMachine.getStartState());
                        stateMachine.getStates().putAll(parsedStatMachine.getStates());
                        item.setValue(stateMachine);
                        stateMachineMapById.put(stateMachine.getName() + "_" + stateMachine.getTenantId(),
                            item);
                    }
                }
            }
        }
        return item.getValue();
    }

    @Override
    public StateMachine getStateMachine(String stateMachineName, String tenantId) {
        // 优先根据状态机名称和租户ID从内存Map中获取
        Item item = CollectionUtils.computeIfAbsent(stateMachineMapByNameAndTenant, stateMachineName + "_" + tenantId,
            key -> new Item());
        if (item.getValue() == null && stateLangStore != null) {
            synchronized (item) {
                if (item.getValue() == null) {
                    // 如果走内存Map获取不到，则从DB中获取
                    StateMachine stateMachine = stateLangStore.getLastVersionStateMachine(stateMachineName, tenantId);
                    if (stateMachine != null) {
                        StateMachine parsedStatMachine = StateMachineParserFactory.getStateMachineParser(jsonParserName).parse(
                            stateMachine.getContent());
                        if (parsedStatMachine == null) {
                            throw new RuntimeException(
                                "Parse State Language failed, stateMachineId:" + stateMachine.getId() + ", name:"
                                    + stateMachine.getName());
                        }
                        stateMachine.setStartState(parsedStatMachine.getStartState());
                        stateMachine.getStates().putAll(parsedStatMachine.getStates());
                        item.setValue(stateMachine);
                        stateMachineMapById.put(stateMachine.getId(), item);
                    }

                }
            }
        }
        return item.getValue();
    }

    @Override
    public StateMachine getStateMachine(String stateMachineName, String tenantId, String version) {
        throw new UnsupportedOperationException("not implement yet");
    }

    @Override
    public StateMachine registryStateMachine(StateMachine stateMachine) {

        String stateMachineName = stateMachine.getName();
        String tenantId = stateMachine.getTenantId();

        // 流程定义存储
        if (stateLangStore != null) {
            // 使用流程定义存储组件 根据状态机名称、租户ID 查询已有的最新版本的旧的状态机
            StateMachine oldStateMachine = stateLangStore.getLastVersionStateMachine(stateMachineName, tenantId);

            if (oldStateMachine != null) {
                byte[] oldBytesContent = null;
                byte[] bytesContent = null;
                try {
                    oldBytesContent = oldStateMachine.getContent().getBytes(charset);
                    bytesContent = stateMachine.getContent().getBytes(charset);
                } catch (UnsupportedEncodingException e) {
                    LOGGER.error(e.getMessage(), e);
                }
                // 旧状态机内容和新状态机内容一样，将旧状态机的ID、创建时间赋值给新状态机，直接return新状态机
                if (Arrays.equals(bytesContent, oldBytesContent) && stateMachine.getVersion() != null && stateMachine
                    .getVersion().equals(oldStateMachine.getVersion())) {

                    LOGGER.info("StateMachine[{}] is already exist a same version", stateMachineName);

                    stateMachine.setId(oldStateMachine.getId());
                    stateMachine.setGmtCreate(oldStateMachine.getGmtCreate());

                    Item item = new Item(stateMachine);
                    stateMachineMapByNameAndTenant.put(stateMachineName + "_" + tenantId, item);
                    stateMachineMapById.put(stateMachine.getId(), item);
                    return stateMachine;
                }
            }
            // 如果状态机的ID为空，则使用序号生成器生成一个ID
            if (StringUtils.isBlank(stateMachine.getId())) {
                stateMachine.setId(seqGenerator.generate(DomainConstants.SEQ_ENTITY_STATE_MACHINE));
            }
            // 设置创建时间
            stateMachine.setGmtCreate(new Date());
            // 将状态机存储
            stateLangStore.storeStateMachine(stateMachine);
        }

        // 如果状态机的ID为空，则使用序号生成器生成一个ID
        if (StringUtils.isBlank(stateMachine.getId())) {
            stateMachine.setId(seqGenerator.generate(DomainConstants.SEQ_ENTITY_STATE_MACHINE));
        }

        Item item = new Item(stateMachine);
        // 将状态机数据放在内存中（以状态机名称_租户ID为key、以状态ID为key，状态机为value）
        stateMachineMapByNameAndTenant.put(stateMachineName + "_" + tenantId, item);
        stateMachineMapById.put(stateMachine.getId(), item);
        return stateMachine;
    }

    @Override
    public void registryByResources(Resource[] resources, String tenantId) throws IOException {
        if (resources != null) {
            // 遍历所有的状态机定义JSON文件
            for (Resource resource : resources) {
                String json;
                // 1> 基于IO流将状态机定义JSON文件 读取成响应字符集的JSON字符串
                try (InputStream is = resource.getInputStream()) {
                    json = IOUtils.toString(is, charset);
                }
                // 2> 从状态机解析工厂中根据 JSON解析名称 获取一个状态机解析器，并对JSON字符串进行解析，解析完之后可以拿到一个状态机
                StateMachine stateMachine = StateMachineParserFactory.getStateMachineParser(jsonParserName).parse(json);
                if (stateMachine != null) {
                    stateMachine.setContent(json);
                    if (StringUtils.isBlank(stateMachine.getTenantId())) {
                        stateMachine.setTenantId(tenantId);
                    }
                    registryStateMachine(stateMachine);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("===== StateMachine Loaded: \n{}", json);
                    }
                }
            }
        }
    }

    public void setStateLangStore(StateLangStore stateLangStore) {
        this.stateLangStore = stateLangStore;
    }

    public void setSeqGenerator(SeqGenerator seqGenerator) {
        this.seqGenerator = seqGenerator;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public String getJsonParserName() {
        return jsonParserName;
    }

    public void setJsonParserName(String jsonParserName) {
        this.jsonParserName = jsonParserName;
    }

    private static class Item {

        private StateMachine value;

        private Item() {
        }

        private Item(StateMachine value) {
            this.value = value;
        }

        public StateMachine getValue() {
            return value;
        }

        public void setValue(StateMachine value) {
            this.value = value;
        }
    }
}