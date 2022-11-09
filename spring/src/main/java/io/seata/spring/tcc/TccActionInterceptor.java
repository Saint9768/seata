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
package io.seata.spring.tcc;

import java.lang.reflect.Method;
import javax.annotation.Nullable;

import io.seata.common.DefaultValues;
import io.seata.config.ConfigurationChangeEvent;
import io.seata.config.ConfigurationChangeListener;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.context.RootContext;
import io.seata.core.model.BranchType;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;
import io.seata.rm.tcc.interceptor.ActionInterceptorHandler;
import io.seata.rm.tcc.remoting.RemotingDesc;
import io.seata.rm.tcc.remoting.parser.DubboUtil;
import io.seata.spring.util.SpringProxyUtils;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

import static io.seata.common.DefaultValues.DEFAULT_DISABLE_GLOBAL_TRANSACTION;
import static io.seata.core.constants.ConfigurationKeys.TCC_ACTION_INTERCEPTOR_ORDER;

/**
 * TCC Interceptor
 *
 * @author zhangsen
 * @author wang.liang
 */
public class TccActionInterceptor implements MethodInterceptor, ConfigurationChangeListener, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(TccActionInterceptor.class);
    private static final int ORDER_NUM = ConfigurationFactory.getInstance().getInt(TCC_ACTION_INTERCEPTOR_ORDER,
            DefaultValues.TCC_ACTION_INTERCEPTOR_ORDER);

    // TCC action拦截处理处理
    private ActionInterceptorHandler actionInterceptorHandler = new ActionInterceptorHandler();

    // 是否禁用全局事务
    private volatile boolean disable = ConfigurationFactory.getInstance().getBoolean(
        ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION, DEFAULT_DISABLE_GLOBAL_TRANSACTION);

    /**
     * remoting bean info
     */
    protected RemotingDesc remotingDesc;

    /**
     * Instantiates a new Tcc action interceptor.
     */
    public TccActionInterceptor() {
    }

    /**
     * Instantiates a new Tcc action interceptor.
     *
     * @param remotingDesc the remoting desc
     */
    public TccActionInterceptor(RemotingDesc remotingDesc) {
        this.remotingDesc = remotingDesc;
    }

    @Override
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        // 如果当前事务不处于全局事务中 或 禁用了全局事务 或 当前事务模式为SAGA，则直接执行目标方法；
        if (!RootContext.inGlobalTransaction() || disable || RootContext.inSagaBranch()) {
            //not in transaction, or this interceptor is disabled
            return invocation.proceed();
        }
        // 获取本次要调用的方法，在class接口中的体现
        Method method = getActionInterfaceMethod(invocation);
        TwoPhaseBusinessAction businessAction = method.getAnnotation(TwoPhaseBusinessAction.class);
        // try method，方法必须要标注@TwoPhaseBusinessAction注解才会走TCC逻辑
        if (businessAction != null) {
            // save the xid
            String xid = RootContext.getXID();
            //save the previous branchType
            BranchType previousBranchType = RootContext.getBranchType();
            //if not TCC, bind TCC branchType
            if (BranchType.TCC != previousBranchType) {
                // 将TCC事务模式绑定到RootContext
                RootContext.bindBranchType(BranchType.TCC);
            }
            try {
                // Handler the TCC Aspect, and return the business result
                // 将全局事务xid、方法的入参、@TwoPhaseBusinessAction注解内容、目标业务方法的执行传递到`ActionInterceptorHandler#proceed()`方法中进行TCC切面的处理、业务的执行。
                return actionInterceptorHandler.proceed(
                        method, // 目标业务方法
                        invocation.getArguments(), // 调用方法时传递进来的参数
                        xid, // 全局事务xid
                        businessAction, // 标注的@TwoPhaseBusinessAction注解内容
                        invocation::proceed); // 目标业务方法的执行
            } finally {
                //if not TCC, unbind branchType
                if (BranchType.TCC != previousBranchType) {
                    RootContext.unbindBranchType();
                }
                //MDC remove branchId
                MDC.remove(RootContext.MDC_KEY_BRANCH_ID);
            }
        }

        //not TCC try method
        return invocation.proceed();
    }

    /**
     * get the method from interface
     *
     * @param invocation the invocation
     * @return the action interface method
     */
    protected Method getActionInterfaceMethod(MethodInvocation invocation) {
        Class<?> interfaceType = null;
        try {
            if (remotingDesc == null) {
                interfaceType = getProxyInterface(invocation.getThis());
            } else {
                interfaceType = remotingDesc.getInterfaceClass();
            }
            if (interfaceType == null && remotingDesc != null && remotingDesc.getInterfaceClassName() != null) {
                interfaceType = Class.forName(remotingDesc.getInterfaceClassName(), true,
                    Thread.currentThread().getContextClassLoader());
            }
            if (interfaceType == null) {
                return invocation.getMethod();
            }
            return interfaceType.getMethod(invocation.getMethod().getName(),
                invocation.getMethod().getParameterTypes());
        } catch (NoSuchMethodException e) {
            if (interfaceType != null && !"toString".equals(invocation.getMethod().getName())) {
                LOGGER.warn("no such method '{}' from interface {}", invocation.getMethod().getName(), interfaceType.getName());
            }
            return invocation.getMethod();
        } catch (Exception e) {
            LOGGER.warn("get Method from interface failed", e);
            return invocation.getMethod();
        }
    }

    /**
     * get the interface of proxy
     *
     * @param proxyBean the proxy bean
     * @return proxy interface
     * @throws Exception the exception
     */
    @Nullable
    protected Class<?> getProxyInterface(Object proxyBean) throws Exception {
        if (DubboUtil.isDubboProxyName(proxyBean.getClass().getName())) {
            //dubbo javaassist proxy
            return DubboUtil.getAssistInterface(proxyBean);
        } else {
            //jdk/cglib proxy
            return SpringProxyUtils.getTargetInterface(proxyBean);
        }
    }

    @Override
    public void onChangeEvent(ConfigurationChangeEvent event) {
        if (ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION.equals(event.getDataId())) {
            LOGGER.info("{} config changed, old value:{}, new value:{}", ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                disable, event.getNewValue());
            disable = Boolean.parseBoolean(event.getNewValue().trim());
        }
    }

    @Override
    public int getOrder() {
        return ORDER_NUM;
    }
}
