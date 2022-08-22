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
package io.seata.server;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.seata.common.XID;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.common.util.NetUtil;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.rpc.netty.NettyRemotingServer;
import io.seata.core.rpc.netty.NettyServerConfig;
import io.seata.server.coordinator.DefaultCoordinator;
import io.seata.server.lock.LockerManagerFactory;
import io.seata.server.metrics.MetricsManager;
import io.seata.server.session.SessionHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.seata.spring.boot.autoconfigure.StarterConstants.REGEX_SPLIT_CHAR;
import static io.seata.spring.boot.autoconfigure.StarterConstants.REGISTRY_PREFERED_NETWORKS;

/**
 * The type Server.
 *
 * @author slievrly
 */
public class Server {
    /**
     * The entry point of application.
     *
     * @param args the input arguments
     */
    public static void start(String[] args) {
        // create logger
        final Logger logger = LoggerFactory.getLogger(Server.class);

        //initialize the parameter parser
        //Note that the parameter parser should always be the first line to execute.
        //Because, here we need to parse the parameters needed for startup.
        // 1. 对配置文件做参数解析：包括registry.conf、file.conf的解析
        ParameterParser parameterParser = new ParameterParser(args);

        // 2、初始化监控，做metric指标采集
        MetricsManager.get().init();

        // 将Store资源持久化方式放到系统的环境变量store.mode中
        System.setProperty(ConfigurationKeys.STORE_MODE, parameterParser.getStoreMode());

        // seata server里netty server 的io线程池（核心线程数50，最大线程数100）
        ThreadPoolExecutor workingThreads = new ThreadPoolExecutor(NettyServerConfig.getMinServerPoolSize(),
                NettyServerConfig.getMaxServerPoolSize(),
                NettyServerConfig.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(NettyServerConfig.getMaxTaskQueueSize()),
                new NamedThreadFactory("ServerHandlerThread", NettyServerConfig.getMaxServerPoolSize()), new ThreadPoolExecutor.CallerRunsPolicy());

        // 3、创建TC与RM/TM通信的RPC服务器--netty
        NettyRemotingServer nettyRemotingServer = new NettyRemotingServer(workingThreads);

        // 4、初始化UUID生成器（雪花算法）
        UUIDGenerator.init(parameterParser.getServerNode());

        //log store mode : file, db, redis
        // 5、设置事务会话、全局锁的持久化方式，有三种类型可选：file/db/redis
        SessionHolder.init(parameterParser.getSessionStoreMode());
        LockerManagerFactory.init(parameterParser.getLockStoreMode());

        // 6、创建并初始化事务协调器，后台启动一堆线程做定时任务，
        DefaultCoordinator coordinator = DefaultCoordinator.getInstance(nettyRemotingServer);
        coordinator.init();

        // 将DefaultCoordinator作为Netty Server的transactionMessageHandler；
        // 用于做AT、TCC、SAGA等不同事务类型的逻辑处理
        nettyRemotingServer.setHandler(coordinator);

        // let ServerRunner do destroy instead ShutdownHook, see https://github.com/seata/seata/issues/4028
        // 7、注册ServerRunner销毁（Spring容器销毁）的回调钩子函数
        ServerRunner.addDisposable(coordinator);

        //127.0.0.1 and 0.0.0.0 are not valid here.
        if (NetUtil.isValidIp(parameterParser.getHost(), false)) {
            XID.setIpAddress(parameterParser.getHost());
        } else {
            String preferredNetworks = ConfigurationFactory.getInstance().getConfig(REGISTRY_PREFERED_NETWORKS);
            if (StringUtils.isNotBlank(preferredNetworks)) {
                XID.setIpAddress(NetUtil.getLocalIp(preferredNetworks.split(REGEX_SPLIT_CHAR)));
            } else {
                XID.setIpAddress(NetUtil.getLocalIp());
            }
        }
        // 8、启动netty Server，用于接收TM/RM的请求
        nettyRemotingServer.init();
    }
}
