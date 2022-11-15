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
package io.seata.rm.tcc;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import io.seata.common.exception.FrameworkErrorCode;
import io.seata.common.exception.SkipCallbackWrapperException;
import io.seata.common.executor.Callback;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.rm.tcc.constant.TCCFenceConstant;
import io.seata.rm.tcc.exception.TCCFenceException;
import io.seata.rm.tcc.store.TCCFenceDO;
import io.seata.rm.tcc.store.TCCFenceStore;
import io.seata.rm.tcc.store.db.TCCFenceStoreDataBaseDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TCC Fence Handler(idempotent, non_rollback, suspend)
 *
 * @author kaka2code
 */
public class TCCFenceHandler {

    private TCCFenceHandler() {
        throw new IllegalStateException("Utility class");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(TCCFenceHandler.class);

    private static final TCCFenceStore TCC_FENCE_DAO = TCCFenceStoreDataBaseDAO.getInstance();

    private static DataSource dataSource;

    private static TransactionTemplate transactionTemplate;

    private static final int MAX_THREAD_CLEAN = 1;

    private static final int MAX_QUEUE_SIZE = 500;

    private static final LinkedBlockingQueue<FenceLogIdentity> LOG_QUEUE = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    private static FenceLogCleanRunnable fenceLogCleanRunnable;

    private static ExecutorService logCleanExecutor;

    static {
        try {
            initLogCleanExecutor();
        } catch (Exception e) {
            LOGGER.error("init fence log clean executor error", e);
        }
    }

    public static void setDataSource(DataSource dataSource) {
        TCCFenceHandler.dataSource = dataSource;
    }

    public static void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        TCCFenceHandler.transactionTemplate = transactionTemplate;
    }

    /**
     * tcc prepare method enhanced
     *
     * @param xid            the global transaction id
     * @param branchId       the branch transaction id
     * @param actionName     the action name
     * @param targetCallback the target callback
     * @return the boolean
     */
    public static Object prepareFence(String xid, Long branchId, String actionName, Callback<Object> targetCallback) {
        return transactionTemplate.execute(status -> {
            try {
                Connection conn = DataSourceUtils.getConnection(dataSource);
                // 尝试新增一条事务控制记录，状态为已尝试，如果新增失败、说明存在悬挂问题，直接抛出异常
                boolean result = insertTCCFenceLog(conn, xid, branchId, actionName, TCCFenceConstant.STATUS_TRIED);
                LOGGER.info("TCC fence prepare result: {}. xid: {}, branchId: {}", result, xid, branchId);
                if (result) {
                    return targetCallback.execute();
                } else {
                    throw new TCCFenceException(String.format("Insert tcc fence record error, prepare fence failed. xid= %s, branchId= %s", xid, branchId),
                            FrameworkErrorCode.InsertRecordError);
                }
            } catch (TCCFenceException e) {
                if (e.getErrcode() == FrameworkErrorCode.DuplicateKeyException) {
                    LOGGER.error("Branch transaction has already rollbacked before,prepare fence failed. xid= {},branchId = {}", xid, branchId);
                    addToLogCleanQueue(xid, branchId);
                }
                status.setRollbackOnly();
                throw new SkipCallbackWrapperException(e);
            } catch (Throwable t) {
                status.setRollbackOnly();
                throw new SkipCallbackWrapperException(t);
            }
        });
    }

    /**
     * tcc commit method enhanced
     *
     * @param commitMethod          commit method
     * @param targetTCCBean         target tcc bean
     * @param xid                   the global transaction id
     * @param branchId              the branch transaction id
     * @param args                  commit method's parameters
     * @return the boolean
     */
    public static boolean commitFence(Method commitMethod, Object targetTCCBean,
                                      String xid, Long branchId, Object[] args) {
        return transactionTemplate.execute(status -> {
            try {
                Connection conn = DataSourceUtils.getConnection(dataSource);
                // 执行TCC事务的二阶段commit()方法时会先根据xid和branch_id判断是否存在TCCFenceLog；
                TCCFenceDO tccFenceDO = TCC_FENCE_DAO.queryTCCFenceDO(conn, xid, branchId);
                // 不存在，则抛出异常
                if (tccFenceDO == null) {
                    throw new TCCFenceException(String.format("TCC fence record not exists, commit fence method failed. xid= %s, branchId= %s", xid, branchId),
                            FrameworkErrorCode.RecordAlreadyExists);
                }
                // 存在，并且状态是已提交，说明是重复提交，直接返回操作成功。
                if (TCCFenceConstant.STATUS_COMMITTED == tccFenceDO.getStatus()) {
                    LOGGER.info("Branch transaction has already committed before. idempotency rejected. xid: {}, branchId: {}, status: {}", xid, branchId, tccFenceDO.getStatus());
                    return true;
                }
                // 存在，并且状态是已回滚、悬挂，打印个日志，直接返回操作失败。
                if (TCCFenceConstant.STATUS_ROLLBACKED == tccFenceDO.getStatus() || TCCFenceConstant.STATUS_SUSPENDED == tccFenceDO.getStatus()) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Branch transaction status is unexpected. xid: {}, branchId: {}, status: {}", xid, branchId, tccFenceDO.getStatus());
                    }
                    return false;
                }
                // 存在，并且状态是已尝试，则更新TCCFenceLog记录状态为已提交。
                return updateStatusAndInvokeTargetMethod(conn, commitMethod, targetTCCBean, xid, branchId, TCCFenceConstant.STATUS_COMMITTED, status, args);
            } catch (Throwable t) {
                status.setRollbackOnly();
                throw new SkipCallbackWrapperException(t);
            }
        });
    }

    /**
     * tcc rollback method enhanced
     *
     * @param rollbackMethod        rollback method
     * @param targetTCCBean         target tcc bean
     * @param xid                   the global transaction id
     * @param branchId              the branch transaction id
     * @param args                  rollback method's parameters
     * @param actionName            the action name
     * @return the boolean
     */
    public static boolean rollbackFence(Method rollbackMethod, Object targetTCCBean,
                                        String xid, Long branchId, Object[] args, String actionName) {
        return transactionTemplate.execute(status -> {
            try {
                Connection conn = DataSourceUtils.getConnection(dataSource);
                // 执行TCC事务的二阶段cancel()方法时会先根据xid和branch_id判断是否存在TCCFenceLog；
                TCCFenceDO tccFenceDO = TCC_FENCE_DAO.queryTCCFenceDO(conn, xid, branchId);
                // non_rollback
                // 空回滚：不存在，则插入一条状态为悬挂的记录；
                if (tccFenceDO == null) {
                    boolean result = insertTCCFenceLog(conn, xid, branchId, actionName, TCCFenceConstant.STATUS_SUSPENDED);
                    LOGGER.info("Insert tcc fence record result: {}. xid: {}, branchId: {}", result, xid, branchId);
                    if (!result) {
                        throw new TCCFenceException(String.format("Insert tcc fence record error, rollback fence method failed. xid= %s, branchId= %s", xid, branchId),
                                FrameworkErrorCode.InsertRecordError);
                    }
                    return true;
                } else {
                    // 存在，并且状态是已回滚、悬挂，说明是重复提交，直接返回操作成功。
                    if (TCCFenceConstant.STATUS_ROLLBACKED == tccFenceDO.getStatus() || TCCFenceConstant.STATUS_SUSPENDED == tccFenceDO.getStatus()) {
                        LOGGER.info("Branch transaction had already rollbacked before, idempotency rejected. xid: {}, branchId: {}, status: {}", xid, branchId, tccFenceDO.getStatus());
                        return true;
                    }
                    // 存在，并且状态是已提交，打印个日志，直接返回操作失败。
                    if (TCCFenceConstant.STATUS_COMMITTED == tccFenceDO.getStatus()) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn("Branch transaction status is unexpected. xid: {}, branchId: {}, status: {}", xid, branchId, tccFenceDO.getStatus());
                        }
                        return false;
                    }
                }
                // 存在，并且状态是已尝试，则更新TCCFenceLog记录状态为已回滚。
                return updateStatusAndInvokeTargetMethod(conn, rollbackMethod, targetTCCBean, xid, branchId, TCCFenceConstant.STATUS_ROLLBACKED, status, args);
            } catch (Throwable t) {
                status.setRollbackOnly();
                throw new SkipCallbackWrapperException(t);
            }
        });
    }

    /**
     * Insert TCC fence log
     *
     * @param conn     the db connection
     * @param xid      the xid
     * @param branchId the branchId
     * @param status   the status
     * @return the boolean
     */
    private static boolean insertTCCFenceLog(Connection conn, String xid, Long branchId, String actionName, Integer status) {
        TCCFenceDO tccFenceDO = new TCCFenceDO();
        tccFenceDO.setXid(xid);
        tccFenceDO.setBranchId(branchId);
        tccFenceDO.setActionName(actionName);
        tccFenceDO.setStatus(status);
        return TCC_FENCE_DAO.insertTCCFenceDO(conn, tccFenceDO);
    }

    /**
     * Update TCC Fence status and invoke target method
     *
     * @param method                target method
     * @param targetTCCBean         target bean
     * @param xid                   the global transaction id
     * @param branchId              the branch transaction id
     * @param status                the tcc fence status
     * @return the boolean
     */
    private static boolean updateStatusAndInvokeTargetMethod(Connection conn, Method method, Object targetTCCBean,
                                                             String xid, Long branchId, int status,
                                                             TransactionStatus transactionStatus,
                                                             Object[] args) throws Exception {
        boolean result = TCC_FENCE_DAO.updateTCCFenceDO(conn, xid, branchId, status, TCCFenceConstant.STATUS_TRIED);
        if (result) {
            // invoke two phase method
            Object ret = method.invoke(targetTCCBean, args);
            if (null != ret) {
                if (ret instanceof TwoPhaseResult) {
                    result = ((TwoPhaseResult) ret).isSuccess();
                } else {
                    result = (boolean) ret;
                }
                // If the business execution result is false, the transaction will be rolled back
                if (!result) {
                    transactionStatus.setRollbackOnly();
                }
            }
        }
        return result;
    }

    /**
     * Delete TCC Fence
     *
     * @param xid      the global transaction id
     * @param branchId the branch transaction id
     * @return the boolean
     */
    public static boolean deleteFence(String xid, Long branchId) {
        return transactionTemplate.execute(status -> {
            boolean ret = false;
            try {
                Connection conn = DataSourceUtils.getConnection(dataSource);
                ret = TCC_FENCE_DAO.deleteTCCFenceDO(conn, xid, branchId);
            } catch (RuntimeException e) {
                status.setRollbackOnly();
                LOGGER.error("delete fence log failed, xid: {}, branchId: {}", xid, branchId, e);
            }
            return ret;
        });
    }

    /**
     * Delete TCC Fence By Datetime
     *
     * @param datetime datetime
     * @return the deleted row count
     */
    public static int deleteFenceByDate(Date datetime) {
        return transactionTemplate.execute(status -> {
            try {
                Connection conn = DataSourceUtils.getConnection(dataSource);
                return TCC_FENCE_DAO.deleteTCCFenceDOByDate(conn, datetime);
            } catch (RuntimeException e) {
                status.setRollbackOnly();
                throw e;
            }
        });
    }

    private static void initLogCleanExecutor() {
        logCleanExecutor = new ThreadPoolExecutor(MAX_THREAD_CLEAN, MAX_THREAD_CLEAN, Integer.MAX_VALUE,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                new NamedThreadFactory("fenceLogCleanThread", MAX_THREAD_CLEAN, true)
        );
        fenceLogCleanRunnable = new FenceLogCleanRunnable();
        logCleanExecutor.submit(fenceLogCleanRunnable);
    }

    private static void addToLogCleanQueue(final String xid, final long branchId) {
        FenceLogIdentity logIdentity = new FenceLogIdentity();
        logIdentity.setXid(xid);
        logIdentity.setBranchId(branchId);
        try {
            LOG_QUEUE.add(logIdentity);
        } catch (Exception e) {
            LOGGER.warn("Insert tcc fence record into queue for async delete error,xid:{},branchId:{}", xid, branchId, e);
        }
    }

    /**
     * clean fence log that has the final status runnable.
     *
     * @see TCCFenceConstant
     */
    private static class FenceLogCleanRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {

                try {
                    FenceLogIdentity logIdentity = LOG_QUEUE.take();
                    boolean ret = TCCFenceHandler.deleteFence(logIdentity.getXid(), logIdentity.getBranchId());
                    if (!ret) {
                        LOGGER.error("delete fence log failed, xid: {}, branchId: {}", logIdentity.getXid(), logIdentity.getBranchId());
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("take fence log from queue for clean be interrupted", e);
                } catch (Exception e) {
                    LOGGER.error("exception occur when clean fence log", e);
                }
            }
        }
    }

    private static class FenceLogIdentity {
        /**
         * the global transaction id
         */
        private String xid;

        /**
         * the branch transaction id
         */
        private Long branchId;

        public String getXid() {
            return xid;
        }

        public Long getBranchId() {
            return branchId;
        }

        public void setXid(String xid) {
            this.xid = xid;
        }

        public void setBranchId(Long branchId) {
            this.branchId = branchId;
        }
    }
}
