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
package io.seata.spring.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.eventbus.Subscribe;
import io.seata.common.exception.ShouldNeverHappenException;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationCache;
import io.seata.config.ConfigurationChangeEvent;
import io.seata.config.ConfigurationChangeListener;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.event.EventBus;
import io.seata.core.event.GuavaEventBus;
import io.seata.core.model.GlobalLockConfig;
import io.seata.spring.event.DegradeCheckEvent;
import io.seata.tm.TransactionManagerHolder;
import io.seata.tm.api.DefaultFailureHandlerImpl;
import io.seata.tm.api.FailureHandler;
import io.seata.rm.GlobalLockExecutor;
import io.seata.rm.GlobalLockTemplate;
import io.seata.tm.api.TransactionalExecutor;
import io.seata.tm.api.TransactionalTemplate;
import io.seata.tm.api.transaction.NoRollbackRule;
import io.seata.tm.api.transaction.RollbackRule;
import io.seata.tm.api.transaction.TransactionInfo;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;

import static io.seata.common.DefaultValues.DEFAULT_DISABLE_GLOBAL_TRANSACTION;
import static io.seata.common.DefaultValues.DEFAULT_GLOBAL_TRANSACTION_TIMEOUT;
import static io.seata.common.DefaultValues.DEFAULT_TM_DEGRADE_CHECK;
import static io.seata.common.DefaultValues.DEFAULT_TM_DEGRADE_CHECK_ALLOW_TIMES;
import static io.seata.common.DefaultValues.DEFAULT_TM_DEGRADE_CHECK_PERIOD;
import static io.seata.common.DefaultValues.TM_INTERCEPTOR_ORDER;

/**
 * The type Global transactional interceptor.
 *
 * @author slievrly
 */
public class GlobalTransactionalInterceptor implements ConfigurationChangeListener, MethodInterceptor, SeataInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalTransactionalInterceptor.class);

    // 默认全局事务异常处理器，如果全局事务在进行开启、提交、重试、回滚异常的时候，则回调进行异常处理；
    private static final FailureHandler DEFAULT_FAIL_HANDLER = new DefaultFailureHandlerImpl();

    // 全局事务生命周期模板管理组件
    private final TransactionalTemplate transactionalTemplate = new TransactionalTemplate();

    // 全局事务锁，不同的全局事务之间去做写隔离
    private final GlobalLockTemplate globalLockTemplate = new GlobalLockTemplate();

    // 真正的全局事务异常处理器
    private final FailureHandler failureHandler;
    // 是否禁用了全局事务
    private volatile boolean disable;
    // 全局事务拦截器的顺序
    private int order;
    // AOP切面全局事务核心配置，数据源自@GlobalTransactional注解
    protected AspectTransactional aspectTransactional;
    // 全局事务降级检查的时间周期
    private static int degradeCheckPeriod;
    // 是否开启全局事务降级检查
    private static volatile boolean degradeCheck;
    // 降级检查允许时间
    private static int degradeCheckAllowTimes;
    // 降级次数
    private static volatile Integer degradeNum = 0;
    // 达标次数
    private static volatile Integer reachNum = 0;
    // guava提供的事件总线
    private static final EventBus EVENT_BUS = new GuavaEventBus("degradeCheckEventBus", true);
    // 定时调度的线程池
    private static ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("degradeCheckWorker", 1, true));

    //region DEFAULT_GLOBAL_TRANSACTION_TIMEOUT，默认全局事务超时时间
    private static int defaultGlobalTransactionTimeout = 0;

    // 初始化默认全局事务超时时间，默认是60s;
    private void initDefaultGlobalTransactionTimeout() {
        if (GlobalTransactionalInterceptor.defaultGlobalTransactionTimeout <= 0) {
            int defaultGlobalTransactionTimeout;
            try {
                // DEFAULT_GLOBAL_TRANSACTION_TIMEOUT是从配置中心取的
                defaultGlobalTransactionTimeout = ConfigurationFactory.getInstance().getInt(
                        ConfigurationKeys.DEFAULT_GLOBAL_TRANSACTION_TIMEOUT, DEFAULT_GLOBAL_TRANSACTION_TIMEOUT);
            } catch (Exception e) {
                LOGGER.error("Illegal global transaction timeout value: " + e.getMessage());
                defaultGlobalTransactionTimeout = DEFAULT_GLOBAL_TRANSACTION_TIMEOUT;
            }
            if (defaultGlobalTransactionTimeout <= 0) {
                LOGGER.warn("Global transaction timeout value '{}' is illegal, and has been reset to the default value '{}'",
                        defaultGlobalTransactionTimeout, DEFAULT_GLOBAL_TRANSACTION_TIMEOUT);
                defaultGlobalTransactionTimeout = DEFAULT_GLOBAL_TRANSACTION_TIMEOUT;
            }
            GlobalTransactionalInterceptor.defaultGlobalTransactionTimeout = defaultGlobalTransactionTimeout;
        }
    }

    public GlobalTransactionalInterceptor() {
        this(null);
    }

    //endregion

    /**
     * Instantiates a new Global transactional interceptor.
     *
     * @param failureHandler the failure handler
     */
    public GlobalTransactionalInterceptor(FailureHandler failureHandler) {
        this.failureHandler = failureHandler == null ? DEFAULT_FAIL_HANDLER : failureHandler;
        this.disable = ConfigurationFactory.getInstance().getBoolean(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                DEFAULT_DISABLE_GLOBAL_TRANSACTION);
        this.order =
                ConfigurationFactory.getInstance().getInt(ConfigurationKeys.TM_INTERCEPTOR_ORDER, TM_INTERCEPTOR_ORDER);
        degradeCheck = ConfigurationFactory.getInstance().getBoolean(ConfigurationKeys.CLIENT_DEGRADE_CHECK,
                DEFAULT_TM_DEGRADE_CHECK);
        // 默认不开启
        if (degradeCheck) {
            ConfigurationCache.addConfigListener(ConfigurationKeys.CLIENT_DEGRADE_CHECK, this);
            degradeCheckPeriod = ConfigurationFactory.getInstance()
                    .getInt(ConfigurationKeys.CLIENT_DEGRADE_CHECK_PERIOD, DEFAULT_TM_DEGRADE_CHECK_PERIOD);
            degradeCheckAllowTimes = ConfigurationFactory.getInstance()
                    .getInt(ConfigurationKeys.CLIENT_DEGRADE_CHECK_ALLOW_TIMES, DEFAULT_TM_DEGRADE_CHECK_ALLOW_TIMES);
            // 将当前对象注册到事件总线上；
            EVENT_BUS.register(this);
            // 如果全局事务降级检查周期和允许时间都 大于0，则开启一个定时任务用于降级检查；
            if (degradeCheckPeriod > 0 && degradeCheckAllowTimes > 0) {
                startDegradeCheck();
            }
        }
        // 初始化默认全局事务超时时间，默认是60s;
        this.initDefaultGlobalTransactionTimeout();
    }

    /**
     * 如果调用@GlobalTransactional注解所在的类 或 方法会走进这里
     *
     * @param methodInvocation
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(final MethodInvocation methodInvocation) throws Throwable {
        // method invocation是一次方法调用，一定是针对某个对象的方法调用；
        // methodInvocation.getThis()就是拿到当前方法所属的对象；
        // AopUtils.getTargetClass()获取到当前实例对象所对应的Class
        Class<?> targetClass =
                methodInvocation.getThis() != null ? AopUtils.getTargetClass(methodInvocation.getThis()) : null;

        // 通过反射获取到被调用目标Class的method方法
        Method specificMethod = ClassUtils.getMostSpecificMethod(methodInvocation.getMethod(), targetClass);

        // 如果目标method不为空，并且方法的DeclaringClass不是Object
        if (specificMethod != null && !specificMethod.getDeclaringClass().equals(Object.class)) {
            // 通过BridgeMethodResolver寻找method的桥接方法
            final Method method = BridgeMethodResolver.findBridgedMethod(specificMethod);
            // 获取目标方法的@GlobalTransactional注解
            final GlobalTransactional globalTransactionalAnnotation =
                    getAnnotation(method, targetClass, GlobalTransactional.class);
            // 如果目标方法被@GlobalLock注解标注，获取到@GlobalLock注解内容
            final GlobalLock globalLockAnnotation = getAnnotation(method, targetClass, GlobalLock.class);
            // 如果禁用了全局事务 或 开启了事务降级检查并且降级检查次数大于等于降级检查允许的次数
            // 则localDisable等价于全局事务被禁用了
            boolean localDisable = disable || (degradeCheck && degradeNum >= degradeCheckAllowTimes);

            // 如果全局事务没有被禁用
            if (!localDisable) {
                // 全局事务注解不为空 或者 AOP切面全局事务核心配置不为空
                if (globalTransactionalAnnotation != null || this.aspectTransactional != null) {
                    AspectTransactional transactional;
                    if (globalTransactionalAnnotation != null) {
                        // 构建一个AOP切面全局事务核心配置，配置的数据从全局事务注解中取
                        transactional = new AspectTransactional(globalTransactionalAnnotation.timeoutMills(),
                                globalTransactionalAnnotation.name(), globalTransactionalAnnotation.rollbackFor(),
                                globalTransactionalAnnotation.rollbackForClassName(),
                                globalTransactionalAnnotation.noRollbackFor(),
                                globalTransactionalAnnotation.noRollbackForClassName(),
                                globalTransactionalAnnotation.propagation(),
                                globalTransactionalAnnotation.lockRetryInterval(),
                                globalTransactionalAnnotation.lockRetryTimes());
                    } else {
                        transactional = this.aspectTransactional;
                    }
                    // 真正处理全局事务的入口
                    return handleGlobalTransaction(methodInvocation, transactional);
                } else if (globalLockAnnotation != null) {
                    // 获取事务锁
                    return handleGlobalLock(methodInvocation, globalLockAnnotation);
                }
            }
        }
        // 直接运行目标方法
        return methodInvocation.proceed();
    }

    private Object handleGlobalLock(final MethodInvocation methodInvocation, final GlobalLock globalLockAnno) throws Throwable {
        return globalLockTemplate.execute(new GlobalLockExecutor() {
            @Override
            public Object execute() throws Throwable {
                return methodInvocation.proceed();
            }

            @Override
            public GlobalLockConfig getGlobalLockConfig() {
                GlobalLockConfig config = new GlobalLockConfig();
                config.setLockRetryInterval(globalLockAnno.lockRetryInterval());
                config.setLockRetryTimes(globalLockAnno.lockRetryTimes());
                return config;
            }
        });
    }

    Object handleGlobalTransaction(final MethodInvocation methodInvocation,
                                   final AspectTransactional aspectTransactional) throws Throwable {
        boolean succeed = true;
        try {
            // 基于全局事务生命周期管理组件，执行全局事务
            return transactionalTemplate.execute(new TransactionalExecutor() {

                // 真正执行目标方法
                @Override
                public Object execute() throws Throwable {
                    return methodInvocation.proceed();
                }

                // 根据全局事务注解获取到事务名称，然后对目标方法做一个格式化的处理
                public String name() {
                    String name = aspectTransactional.getName();
                    if (!StringUtils.isNullOrEmpty(name)) {
                        return name;
                    }
                    return formatMethod(methodInvocation.getMethod());
                }

                // 获取全局事务的信息
                @Override
                public TransactionInfo getTransactionInfo() {
                    // reset the value of timeout
                    int timeout = aspectTransactional.getTimeoutMills();
                    if (timeout <= 0 || timeout == DEFAULT_GLOBAL_TRANSACTION_TIMEOUT) {
                        timeout = defaultGlobalTransactionTimeout;
                    }

                    // 封装全局事务信息
                    TransactionInfo transactionInfo = new TransactionInfo();

                    // 超时时间
                    transactionInfo.setTimeOut(timeout);
                    // 全局事务名称
                    transactionInfo.setName(name());
                    // 事务传播级别
                    transactionInfo.setPropagation(aspectTransactional.getPropagation());
                    // 获取全局锁重试间隔
                    transactionInfo.setLockRetryInterval(aspectTransactional.getLockRetryInterval());
                    // 获取全局锁重试次数
                    transactionInfo.setLockRetryTimes(aspectTransactional.getLockRetryTimes());
                    // 全局事务回滚规则
                    Set<RollbackRule> rollbackRules = new LinkedHashSet<>();
                    for (Class<?> rbRule : aspectTransactional.getRollbackFor()) {
                        rollbackRules.add(new RollbackRule(rbRule));
                    }
                    for (String rbRule : aspectTransactional.getRollbackForClassName()) {
                        rollbackRules.add(new RollbackRule(rbRule));
                    }
                    for (Class<?> rbRule : aspectTransactional.getNoRollbackFor()) {
                        rollbackRules.add(new NoRollbackRule(rbRule));
                    }
                    for (String rbRule : aspectTransactional.getNoRollbackForClassName()) {
                        rollbackRules.add(new NoRollbackRule(rbRule));
                    }
                    transactionInfo.setRollbackRules(rollbackRules);
                    return transactionInfo;
                }
            });
        } catch (TransactionalExecutor.ExecutionException e) {
            TransactionalExecutor.Code code = e.getCode();
            switch (code) {
                case RollbackDone:
                    throw e.getOriginalException();
                case BeginFailure:
                    succeed = false;
                    failureHandler.onBeginFailure(e.getTransaction(), e.getCause());
                    throw e.getCause();
                case CommitFailure:
                    succeed = false;
                    failureHandler.onCommitFailure(e.getTransaction(), e.getCause());
                    throw e.getCause();
                case RollbackFailure:
                    failureHandler.onRollbackFailure(e.getTransaction(), e.getOriginalException());
                    throw e.getOriginalException();
                case RollbackRetrying:
                    failureHandler.onRollbackRetrying(e.getTransaction(), e.getOriginalException());
                    throw e.getOriginalException();
                default:
                    throw new ShouldNeverHappenException(String.format("Unknown TransactionalExecutor.Code: %s", code));
            }
        } finally {
            if (degradeCheck) {
                EVENT_BUS.post(new DegradeCheckEvent(succeed));
            }
        }
    }

    public <T extends Annotation> T getAnnotation(Method method, Class<?> targetClass, Class<T> annotationClass) {
        return Optional.ofNullable(method).map(m -> m.getAnnotation(annotationClass))
                .orElse(Optional.ofNullable(targetClass).map(t -> t.getAnnotation(annotationClass)).orElse(null));
    }

    private String formatMethod(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append("(");

        Class<?>[] params = method.getParameterTypes();
        int in = 0;
        for (Class<?> clazz : params) {
            sb.append(clazz.getName());
            if (++in < params.length) {
                sb.append(", ");
            }
        }
        return sb.append(")").toString();
    }

    @Override
    public void onChangeEvent(ConfigurationChangeEvent event) {
        if (ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION.equals(event.getDataId())) {
            LOGGER.info("{} config changed, old value:{}, new value:{}", ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                    disable, event.getNewValue());
            disable = Boolean.parseBoolean(event.getNewValue().trim());
        } else if (ConfigurationKeys.CLIENT_DEGRADE_CHECK.equals(event.getDataId())) {
            degradeCheck = Boolean.parseBoolean(event.getNewValue());
            if (!degradeCheck) {
                degradeNum = 0;
            }
        }
    }

    /**
     * auto upgrade service detection
     */
    private static void startDegradeCheck() {
        executor.scheduleAtFixedRate(() -> {
            if (degradeCheck) {
                try {
                    String xid = TransactionManagerHolder.get().begin(null, null, "degradeCheck", 60000);
                    TransactionManagerHolder.get().commit(xid);
                    EVENT_BUS.post(new DegradeCheckEvent(true));
                } catch (Exception e) {
                    EVENT_BUS.post(new DegradeCheckEvent(false));
                }
            }
        }, degradeCheckPeriod, degradeCheckPeriod, TimeUnit.MILLISECONDS);
    }

    @Subscribe
    public static void onDegradeCheck(DegradeCheckEvent event) {
        if (event.isRequestSuccess()) {
            if (degradeNum >= degradeCheckAllowTimes) {
                reachNum++;
                if (reachNum >= degradeCheckAllowTimes) {
                    reachNum = 0;
                    degradeNum = 0;
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("the current global transaction has been restored");
                    }
                }
            } else if (degradeNum != 0) {
                degradeNum = 0;
            }
        } else {
            if (degradeNum < degradeCheckAllowTimes) {
                degradeNum++;
                if (degradeNum >= degradeCheckAllowTimes) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("the current global transaction has been automatically downgraded");
                    }
                }
            } else if (reachNum != 0) {
                reachNum = 0;
            }
        }
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public void setOrder(int order) {
        this.order = order;
    }

    @Override
    public SeataInterceptorPosition getPosition() {
        return SeataInterceptorPosition.BeforeTransaction;
    }
}
