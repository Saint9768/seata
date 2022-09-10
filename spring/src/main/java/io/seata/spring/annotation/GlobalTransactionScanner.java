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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationCache;
import io.seata.config.ConfigurationChangeEvent;
import io.seata.config.ConfigurationChangeListener;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.rpc.ShutdownHook;
import io.seata.core.rpc.netty.RmNettyRemotingClient;
import io.seata.core.rpc.netty.TmNettyRemotingClient;
import io.seata.rm.RMClient;
import io.seata.spring.annotation.scannercheckers.PackageScannerChecker;
import io.seata.spring.tcc.TccActionInterceptor;
import io.seata.spring.util.OrderUtil;
import io.seata.spring.util.SpringProxyUtils;
import io.seata.spring.util.TCCBeanParserUtils;
import io.seata.tm.TMClient;
import io.seata.tm.api.FailureHandler;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AdvisedSupport;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

import static io.seata.common.DefaultValues.DEFAULT_DISABLE_GLOBAL_TRANSACTION;
import static io.seata.common.DefaultValues.DEFAULT_TX_GROUP;
import static io.seata.common.DefaultValues.DEFAULT_TX_GROUP_OLD;

/**
 * The type Global transaction scanner.
 * 伴随着Spring容器初始化完毕，会调用这个Bean的初始化逻辑，进而初始化seata client
 * <p>
 * AbstractAutoProxyCreator： Spring框架内动态代理创建组件
 * ConfigurationChangeListener： 关注配置变更事件监听器
 * InitializingBean： Bean初始化回调
 * ApplicationContextAware： 感知到SPring容器
 * DisposableBean： 支持可抛弃Bean
 *
 * @author slievrly
 */
public class GlobalTransactionScanner extends AbstractAutoProxyCreator
        implements ConfigurationChangeListener, InitializingBean, ApplicationContextAware, DisposableBean {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalTransactionScanner.class);

    private static final int AT_MODE = 1;
    private static final int MT_MODE = 2;

    private static final int ORDER_NUM = 1024;
    private static final int DEFAULT_MODE = AT_MODE + MT_MODE;

    private static final String SPRING_TRANSACTION_INTERCEPTOR_CLASS_NAME = "org.springframework.transaction.interceptor.TransactionInterceptor";

    private static final Set<String> PROXYED_SET = new HashSet<>();
    private static final Set<String> EXCLUDE_BEAN_NAME_SET = new HashSet<>();
    private static final Set<ScannerChecker> SCANNER_CHECKER_SET = new LinkedHashSet<>();

    // Spring容器
    private static ConfigurableListableBeanFactory beanFactory;

    // AOP里面对方法进行拦截的拦截器
    private MethodInterceptor interceptor;
    // 针对@GlobalTransactional注解方法的AOP拦截器
    private MethodInterceptor globalTransactionalInterceptor;

    // 应用程id，在xml里配置注入进来的
    private final String applicationId;

    // 分布式事务组
    private final String txServiceGroup;

    // 分布式事务模式，默认是AT模式
    private final int mode;

    // 阿里云里的两个概念，阿里云里进行身份认证和安全访问需要用到的东西
    private static String accessKey;
    private static String secretKey;

    // 是否禁用全局事务，默认是FALSE；
    private volatile boolean disableGlobalTransaction = ConfigurationFactory.getInstance().getBoolean(
            ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION, DEFAULT_DISABLE_GLOBAL_TRANSACTION);

    // 通过CAS + Atomic变量保证即使在多线程环境下，初始化也仅会进行一次；即只有一个线程可以成功的初始化；
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // 失败时的处理组件，全局事务开启、提交、回滚、回滚重试失败的回调入口
    private final FailureHandler failureHandlerHook;

    // Spring容器
    private ApplicationContext applicationContext;


    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param txServiceGroup the tx service group
     */
    public GlobalTransactionScanner(String txServiceGroup) {
        this(txServiceGroup, txServiceGroup, DEFAULT_MODE);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param txServiceGroup the tx service group
     * @param mode           the mode
     */
    public GlobalTransactionScanner(String txServiceGroup, int mode) {
        this(txServiceGroup, txServiceGroup, mode);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId  the application id
     * @param txServiceGroup the default server group
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup) {
        this(applicationId, txServiceGroup, DEFAULT_MODE);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId  the application id
     * @param txServiceGroup the tx service group
     * @param mode           the mode
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup, int mode) {
        this(applicationId, txServiceGroup, mode, null);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId      the application id
     * @param txServiceGroup     the tx service group
     * @param failureHandlerHook the failure handler hook
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup, FailureHandler failureHandlerHook) {
        this(applicationId, txServiceGroup, DEFAULT_MODE, failureHandlerHook);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId      the application id
     * @param txServiceGroup     the tx service group
     * @param mode               the mode
     * @param failureHandlerHook the failure handler hook
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup, int mode,
                                    FailureHandler failureHandlerHook) {
        setOrder(ORDER_NUM);
        // 启动 对目标Class创建动态代理
        setProxyTargetClass(true);
        this.applicationId = applicationId;
        this.txServiceGroup = txServiceGroup;
        this.mode = mode;
        this.failureHandlerHook = failureHandlerHook;
    }

    /**
     * Sets access key.
     *
     * @param accessKey the access key
     */
    public static void setAccessKey(String accessKey) {
        GlobalTransactionScanner.accessKey = accessKey;
    }

    /**
     * Sets secret key.
     *
     * @param secretKey the secret key
     */
    public static void setSecretKey(String secretKey) {
        GlobalTransactionScanner.secretKey = secretKey;
    }

    /**
     * DisposableBean回调接口，Spring容器销毁（程序停止运行时），做一些资源的销毁和释放
     */
    @Override
    public void destroy() {
        ShutdownHook.getInstance().destroyAll();
    }

    /**
     * 初始化Seata客户端， 和 Seata Server建立长连接；
     */
    private void initClient() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Initializing Global Transaction Clients ... ");
        }
        if (DEFAULT_TX_GROUP_OLD.equals(txServiceGroup)) {
            LOGGER.warn("the default value of seata.tx-service-group: {} has already changed to {} since Seata 1.5, " +
                            "please change your default configuration as soon as possible " +
                            "and we don't recommend you to use default tx-service-group's value provided by seata",
                    DEFAULT_TX_GROUP_OLD, DEFAULT_TX_GROUP);
        }
        if (StringUtils.isNullOrEmpty(applicationId) || StringUtils.isNullOrEmpty(txServiceGroup)) {
            throw new IllegalArgumentException(String.format("applicationId: %s, txServiceGroup: %s", applicationId, txServiceGroup));
        }
        //init TM
        TMClient.init(applicationId, txServiceGroup, accessKey, secretKey);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Transaction Manager Client is initialized. applicationId[{}] txServiceGroup[{}]", applicationId, txServiceGroup);
        }
        //init RM
        RMClient.init(applicationId, txServiceGroup);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Resource Manager is initialized. applicationId[{}] txServiceGroup[{}]", applicationId, txServiceGroup);
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Global Transaction Clients are initialized. ");
        }

        // 注册Spring容器销毁回调
        registerSpringShutdownHook();

    }

    private void registerSpringShutdownHook() {
        if (applicationContext instanceof ConfigurableApplicationContext) {
            ((ConfigurableApplicationContext) applicationContext).registerShutdownHook();
            ShutdownHook.removeRuntimeShutdownHook();
        }
        ShutdownHook.getInstance().addDisposable(TmNettyRemotingClient.getInstance(applicationId, txServiceGroup));
        ShutdownHook.getInstance().addDisposable(RmNettyRemotingClient.getInstance(applicationId, txServiceGroup));
    }

    /**
     * The following will be scanned, and added corresponding interceptor:
     * 下面的东西将被扫描，并增加相应的拦截器；
     * TM:
     *
     * @see io.seata.spring.annotation.GlobalTransactional // TM annotation
     * Corresponding interceptor:
     * @see io.seata.spring.annotation.GlobalTransactionalInterceptor#handleGlobalTransaction(MethodInvocation, AspectTransactional) // TM handler
     * <p>
     * GlobalLock:
     * @see io.seata.spring.annotation.GlobalLock // GlobalLock annotation
     * Corresponding interceptor:
     * @see io.seata.spring.annotation.GlobalTransactionalInterceptor#handleGlobalLock(MethodInvocation, GlobalLock)  // GlobalLock handler
     * <p>
     * TCC mode:
     * @see io.seata.rm.tcc.api.LocalTCC // TCC annotation on interface
     * @see io.seata.rm.tcc.api.TwoPhaseBusinessAction // TCC annotation on try method
     * @see io.seata.rm.tcc.remoting.RemotingParser // Remote TCC service parser
     * Corresponding interceptor:
     * @see io.seata.spring.tcc.TccActionInterceptor // the interceptor of TCC mode
     */
    // 对于扫描到的@GlobalTransactional注解， bean和beanName
    // 判断类 或 类的某一个方法是否被@GlobalTransactional注解标注，进而决定当前Class是否需要创建动态代理；存在则创建。
    @Override
    protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
        // do checkers，做一些检查，不用花过多精力关注
        if (!doCheckers(bean, beanName)) {
            return bean;
        }

        try {
            synchronized (PROXYED_SET) {
                if (PROXYED_SET.contains(beanName)) {
                    return bean;
                }
                interceptor = null;
                //check TCC proxy TCC的动态代理
                if (TCCBeanParserUtils.isTccAutoProxy(bean, beanName, applicationContext)) {
                    // init tcc fence clean task if enable useTccFence
                    TCCBeanParserUtils.initTccFenceCleanTask(TCCBeanParserUtils.getRemotingDesc(beanName), applicationContext);
                    //TCC interceptor, proxy bean of sofa:reference/dubbo:reference, and LocalTCC
                    interceptor = new TccActionInterceptor(TCCBeanParserUtils.getRemotingDesc(beanName));
                    ConfigurationCache.addConfigListener(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                            (ConfigurationChangeListener) interceptor);
                } else {
                    // 先获取目标Class的接口
                    Class<?> serviceInterface = SpringProxyUtils.findTargetClass(bean);
                    Class<?>[] interfacesIfJdk = SpringProxyUtils.findInterfaces(bean);

                    // existsAnnotation()表示类或类方法是否有被@GlobalTransactional注解标注，进而决定类是否需要被动态代理
                    if (!existsAnnotation(new Class[]{serviceInterface})
                            && !existsAnnotation(interfacesIfJdk)) {
                        return bean;
                    }

                    if (globalTransactionalInterceptor == null) {
                        // 构建一个全局拦截器
                        globalTransactionalInterceptor = new GlobalTransactionalInterceptor(failureHandlerHook);
                        ConfigurationCache.addConfigListener(
                                ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                                (ConfigurationChangeListener) globalTransactionalInterceptor);
                    }
                    interceptor = globalTransactionalInterceptor;
                }

                LOGGER.info("Bean[{}] with name [{}] would use interceptor [{}]", bean.getClass().getName(), beanName, interceptor.getClass().getName());
                // 如果当前Bean没有被AOP代理
                if (!AopUtils.isAopProxy(bean)) {
                    // 基于Spring AOP的AutoProxyCreator对当前Class创建全局事务动态动态代理类
                    bean = super.wrapIfNecessary(bean, beanName, cacheKey);
                } else {
                    AdvisedSupport advised = SpringProxyUtils.getAdvisedSupport(bean);
                    Advisor[] advisor = buildAdvisors(beanName, getAdvicesAndAdvisorsForBean(null, null, null));
                    int pos;
                    for (Advisor avr : advisor) {
                        // Find the position based on the advisor's order, and add to advisors by pos
                        // 找到seata切面的位置
                        pos = findAddSeataAdvisorPosition(advised, avr);
                        advised.addAdvisor(pos, avr);
                    }
                }
                PROXYED_SET.add(beanName);
                return bean;
            }
        } catch (Exception exx) {
            throw new RuntimeException(exx);
        }
    }

    private boolean doCheckers(Object bean, String beanName) {
        if (PROXYED_SET.contains(beanName) || EXCLUDE_BEAN_NAME_SET.contains(beanName)
                || FactoryBean.class.isAssignableFrom(bean.getClass())) {
            return false;
        }

        if (!SCANNER_CHECKER_SET.isEmpty()) {
            for (ScannerChecker checker : SCANNER_CHECKER_SET) {
                try {
                    if (!checker.check(bean, beanName, beanFactory)) {
                        // failed check, do not scan this bean
                        return false;
                    }
                } catch (Exception e) {
                    LOGGER.error("Do check failed: beanName={}, checker={}",
                            beanName, checker.getClass().getSimpleName(), e);
                }
            }
        }

        return true;
    }


    //region the methods about findAddSeataAdvisorPosition  START

    /**
     * Find pos for `advised.addAdvisor(pos, avr);`
     *
     * @param advised      the advised
     * @param seataAdvisor the seata advisor
     * @return the pos
     */
    private int findAddSeataAdvisorPosition(AdvisedSupport advised, Advisor seataAdvisor) {
        // Get seataAdvisor's order and interceptorPosition
        int seataOrder = OrderUtil.getOrder(seataAdvisor);
        SeataInterceptorPosition seataInterceptorPosition = getSeataInterceptorPosition(seataAdvisor);

        // If the interceptorPosition is any, check lowest or highest.
        if (SeataInterceptorPosition.Any == seataInterceptorPosition) {
            if (seataOrder == Ordered.LOWEST_PRECEDENCE) {
                // the last position
                return advised.getAdvisors().length;
            } else if (seataOrder == Ordered.HIGHEST_PRECEDENCE) {
                // the first position
                return 0;
            }
        } else {
            // If the interceptorPosition is not any, compute position if has TransactionInterceptor.
            Integer position = computePositionIfHasTransactionInterceptor(advised, seataAdvisor, seataInterceptorPosition, seataOrder);
            if (position != null) {
                // the position before or after TransactionInterceptor
                return position;
            }
        }

        // Find position
        return this.findPositionInAdvisors(advised.getAdvisors(), seataAdvisor);
    }

    @Nullable
    private Integer computePositionIfHasTransactionInterceptor(AdvisedSupport advised, Advisor seataAdvisor, SeataInterceptorPosition seataInterceptorPosition, int seataOrder) {
        // Find the TransactionInterceptor's advisor, order and position
        Advisor otherAdvisor = null;
        Integer transactionInterceptorPosition = null;
        Integer transactionInterceptorOrder = null;
        for (int i = 0, l = advised.getAdvisors().length; i < l; ++i) {
            otherAdvisor = advised.getAdvisors()[i];
            if (isTransactionInterceptor(otherAdvisor)) {
                transactionInterceptorPosition = i;
                transactionInterceptorOrder = OrderUtil.getOrder(otherAdvisor);
                break;
            }
        }
        // If the TransactionInterceptor does not exist, return null
        if (transactionInterceptorPosition == null) {
            return null;
        }

        // Reset seataOrder if the seataOrder is not match the position
        Advice seataAdvice = seataAdvisor.getAdvice();
        if (SeataInterceptorPosition.AfterTransaction == seataInterceptorPosition && OrderUtil.higherThan(seataOrder, transactionInterceptorOrder)) {
            int newSeataOrder = OrderUtil.lower(transactionInterceptorOrder, 1);
            ((SeataInterceptor) seataAdvice).setOrder(newSeataOrder);
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("The {}'s order '{}' is higher or equals than {}'s order '{}' , reset {}'s order to lower order '{}'.",
                        seataAdvice.getClass().getSimpleName(), seataOrder,
                        otherAdvisor.getAdvice().getClass().getSimpleName(), transactionInterceptorOrder,
                        seataAdvice.getClass().getSimpleName(), newSeataOrder);
            }
            // the position after the TransactionInterceptor's advisor
            return transactionInterceptorPosition + 1;
        } else if (SeataInterceptorPosition.BeforeTransaction == seataInterceptorPosition && OrderUtil.lowerThan(seataOrder, transactionInterceptorOrder)) {
            int newSeataOrder = OrderUtil.higher(transactionInterceptorOrder, 1);
            ((SeataInterceptor) seataAdvice).setOrder(newSeataOrder);
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("The {}'s order '{}' is lower or equals than {}'s order '{}' , reset {}'s order to higher order '{}'.",
                        seataAdvice.getClass().getSimpleName(), seataOrder,
                        otherAdvisor.getAdvice().getClass().getSimpleName(), transactionInterceptorOrder,
                        seataAdvice.getClass().getSimpleName(), newSeataOrder);
            }
            // the position before the TransactionInterceptor's advisor
            return transactionInterceptorPosition;
        }

        return null;
    }

    private int findPositionInAdvisors(Advisor[] advisors, Advisor seataAdvisor) {
        Advisor advisor;
        for (int i = 0, l = advisors.length; i < l; ++i) {
            advisor = advisors[i];
            if (OrderUtil.higherOrEquals(seataAdvisor, advisor)) {
                // the position before the current advisor
                return i;
            }
        }

        // the last position, after all the advisors
        return advisors.length;
    }

    private SeataInterceptorPosition getSeataInterceptorPosition(Advisor seataAdvisor) {
        Advice seataAdvice = seataAdvisor.getAdvice();
        if (seataAdvice instanceof SeataInterceptor) {
            return ((SeataInterceptor) seataAdvice).getPosition();
        } else {
            return SeataInterceptorPosition.Any;
        }
    }

    private boolean isTransactionInterceptor(Advisor advisor) {
        return SPRING_TRANSACTION_INTERCEPTOR_CLASS_NAME.equals(advisor.getAdvice().getClass().getName());
    }

    //endregion the methods about findAddSeataAdvisorPosition  END


    private boolean existsAnnotation(Class<?>[] classes) {
        if (CollectionUtils.isNotEmpty(classes)) {
            for (Class<?> clazz : classes) {
                if (clazz == null) {
                    continue;
                }
                // 目标Class是否被注解@GlobalTransactional标注
                GlobalTransactional trxAnno = clazz.getAnnotation(GlobalTransactional.class);
                if (trxAnno != null) {
                    // 被标注直接返回true
                    return true;
                }

                // 目标Class没有被注解@GlobalTransactional标注，则利用反射拿到目标Class的全部方法
                Method[] methods = clazz.getMethods();
                for (Method method : methods) {
                    // 判断方法是否被@GlobalTransactional注解标注
                    trxAnno = method.getAnnotation(GlobalTransactional.class);
                    if (trxAnno != null) {
                        // 如果类中有方法被@GlobalTransactional注解标注，返回true；
                        return true;
                    }

                    // 判断方法是否被@GlobalLock注解标注，如果被标注，则返回true
                    GlobalLock lockAnno = method.getAnnotation(GlobalLock.class);
                    if (lockAnno != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private MethodDesc makeMethodDesc(GlobalTransactional anno, Method method) {
        return new MethodDesc(anno, method);
    }

    @Override
    protected Object[] getAdvicesAndAdvisorsForBean(Class beanClass, String beanName, TargetSource customTargetSource)
            throws BeansException {
        return new Object[]{interceptor};
    }

    /**
     * InitializingBean回调接口，Spring容器启动和Bean初始化完毕之后的一个回调
     */
    @Override
    public void afterPropertiesSet() {
        // 看看是否禁用了全局事务
        if (disableGlobalTransaction) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Global transaction is disabled.");
            }
            ConfigurationCache.addConfigListener(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                    (ConfigurationChangeListener) this);
            return;
        }
        // CAS保证初始化方法只会被调用一次；
        if (initialized.compareAndSet(false, true)) {
            // 初始化seata Client
            initClient();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.setBeanFactory(applicationContext);
    }

    @Override
    public void onChangeEvent(ConfigurationChangeEvent event) {
        if (ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION.equals(event.getDataId())) {
            disableGlobalTransaction = Boolean.parseBoolean(event.getNewValue().trim());
            if (!disableGlobalTransaction && initialized.compareAndSet(false, true)) {
                LOGGER.info("{} config changed, old value:true, new value:{}", ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                        event.getNewValue());
                initClient();
                ConfigurationCache.removeConfigListener(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION, this);
            }
        }
    }

    public static void setBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        GlobalTransactionScanner.beanFactory = beanFactory;
    }

    public static void addScannablePackages(String... packages) {
        PackageScannerChecker.addScannablePackages(packages);
    }

    public static void addScannerCheckers(Collection<ScannerChecker> scannerCheckers) {
        if (CollectionUtils.isNotEmpty(scannerCheckers)) {
            scannerCheckers.remove(null);
            SCANNER_CHECKER_SET.addAll(scannerCheckers);
        }
    }

    public static void addScannerCheckers(ScannerChecker... scannerCheckers) {
        if (ArrayUtils.isNotEmpty(scannerCheckers)) {
            addScannerCheckers(Arrays.asList(scannerCheckers));
        }
    }

    public static void addScannerExcludeBeanNames(String... beanNames) {
        if (ArrayUtils.isNotEmpty(beanNames)) {
            EXCLUDE_BEAN_NAME_SET.addAll(Arrays.asList(beanNames));
        }
    }
}
