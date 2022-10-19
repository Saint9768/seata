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
package io.seata.rm.datasource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.concurrent.Callable;

import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.exception.TransactionException;
import io.seata.core.exception.TransactionExceptionCode;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.rm.DefaultResourceManager;
import io.seata.rm.datasource.exec.LockConflictException;
import io.seata.rm.datasource.exec.LockRetryController;
import io.seata.rm.datasource.undo.SQLUndoLog;
import io.seata.rm.datasource.undo.UndoLogManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.seata.common.DefaultValues.DEFAULT_CLIENT_LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT;
import static io.seata.common.DefaultValues.DEFAULT_CLIENT_REPORT_RETRY_COUNT;
import static io.seata.common.DefaultValues.DEFAULT_CLIENT_REPORT_SUCCESS_ENABLE;

/**
 * The type Connection proxy.
 *
 * @author sharajava
 */
public class ConnectionProxy extends AbstractConnectionProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionProxy.class);

    private final ConnectionContext context = new ConnectionContext();

    private final LockRetryPolicy lockRetryPolicy = new LockRetryPolicy(this);

    private static final int REPORT_RETRY_COUNT = ConfigurationFactory.getInstance().getInt(
            ConfigurationKeys.CLIENT_REPORT_RETRY_COUNT, DEFAULT_CLIENT_REPORT_RETRY_COUNT);

    public static final boolean IS_REPORT_SUCCESS_ENABLE = ConfigurationFactory.getInstance().getBoolean(
            ConfigurationKeys.CLIENT_REPORT_SUCCESS_ENABLE, DEFAULT_CLIENT_REPORT_SUCCESS_ENABLE);

    /**
     * Instantiates a new Connection proxy.
     *
     * @param dataSourceProxy  the data source proxy
     * @param targetConnection the target connection
     */
    public ConnectionProxy(DataSourceProxy dataSourceProxy, Connection targetConnection) {
        super(dataSourceProxy, targetConnection);
    }

    /**
     * Gets context.
     *
     * @return the context
     */
    public ConnectionContext getContext() {
        return context;
    }

    /**
     * Bind.
     *
     * @param xid the xid
     */
    public void bind(String xid) {
        context.bind(xid);
    }

    /**
     * set global lock requires flag
     *
     * @param isLock whether to lock
     */
    public void setGlobalLockRequire(boolean isLock) {
        context.setGlobalLockRequire(isLock);
    }

    /**
     * get global lock requires flag
     *
     * @return the boolean
     */
    public boolean isGlobalLockRequire() {
        return context.isGlobalLockRequire();
    }

    /**
     * Check lock.
     *
     * @param lockKeys the lockKeys
     * @throws SQLException the sql exception
     */
    public void checkLock(String lockKeys) throws SQLException {
        if (StringUtils.isBlank(lockKeys)) {
            return;
        }
        // Just check lock without requiring lock by now.
        try {
            // 检查全局锁是否被使用
            boolean lockable = DefaultResourceManager.get().lockQuery(
                    BranchType.AT,
                    getDataSourceProxy().getResourceId(),
                    context.getXid(),
                    lockKeys);
            if (!lockable) {
                throw new LockConflictException(String.format("get lock failed, lockKey: %s", lockKeys));
            }
        } catch (TransactionException e) {
            recognizeLockKeyConflictException(e, lockKeys);
        }
    }

    /**
     * Lock query.
     *
     * @param lockKeys the lock keys
     * @return the boolean
     * @throws SQLException the sql exception
     */
    public boolean lockQuery(String lockKeys) throws SQLException {
        // Just check lock without requiring lock by now.
        boolean result = false;
        try {
            result = DefaultResourceManager.get().lockQuery(BranchType.AT, getDataSourceProxy().getResourceId(),
                    context.getXid(), lockKeys);
        } catch (TransactionException e) {
            recognizeLockKeyConflictException(e, lockKeys);
        }
        return result;
    }

    private void recognizeLockKeyConflictException(TransactionException te) throws SQLException {
        recognizeLockKeyConflictException(te, null);
    }

    // 识别锁key冲突异常
    private void recognizeLockKeyConflictException(TransactionException te, String lockKeys) throws SQLException {
        // 全局锁冲突
        if (te.getCode() == TransactionExceptionCode.LockKeyConflict
                || te.getCode() == TransactionExceptionCode.LockKeyConflictFailFast) {
            StringBuilder reasonBuilder = new StringBuilder("get global lock fail, xid:");
            reasonBuilder.append(context.getXid());
            if (StringUtils.isNotBlank(lockKeys)) {
                reasonBuilder.append(", lockKeys:").append(lockKeys);
            }
            throw new LockConflictException(reasonBuilder.toString(), te.getCode());
        } else {
            // 其他异常
            throw new SQLException(te);
        }

    }

    /**
     * append sqlUndoLog
     *
     * @param sqlUndoLog the sql undo log
     */
    public void appendUndoLog(SQLUndoLog sqlUndoLog) {
        context.appendUndoItem(sqlUndoLog);
    }

    /**
     * append lockKey
     *
     * @param lockKey the lock key
     */
    public void appendLockKey(String lockKey) {
        context.appendLockKey(lockKey);
    }

    @Override
    public void commit() throws SQLException {
        try {
            // 由LockRetryPolicy负责提交事务，LockRetryPolicy中包含全局锁的概念，支持retry重试策略
            lockRetryPolicy.execute(() -> {
                doCommit();
                return null;
            });
        } catch (SQLException e) {
            if (targetConnection != null && !getAutoCommit() && !getContext().isAutoCommitChanged()) {
                rollback();
            }
            throw e;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        Savepoint savepoint = targetConnection.setSavepoint();
        context.appendSavepoint(savepoint);
        return savepoint;
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        Savepoint savepoint = targetConnection.setSavepoint(name);
        context.appendSavepoint(savepoint);
        return savepoint;
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        targetConnection.rollback(savepoint);
        context.removeSavepoint(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        targetConnection.releaseSavepoint(savepoint);
        context.releaseSavepoint(savepoint);
    }


    private void doCommit() throws SQLException {
        // 当前DML操作在全局事务中时，判定条件：ConnectionContext中包含xid
        if (context.inGlobalTransaction()) {
            processGlobalTransactionCommit();
        } else if (context.isGlobalLockRequire()) {
            // 如果使用了@GlobalLock，需要获取全局锁
            processLocalCommitWithGlobalLocks();
        } else {
            // 不在分布式事务中，则以原生connection提交本地事务
            targetConnection.commit();
        }
    }

    private void processLocalCommitWithGlobalLocks() throws SQLException {
        // 检查全局锁keys，拼接后的keys以;分隔
        checkLock(context.buildLockKeys());
        try {
            // 以原生connection提交本地事务
            targetConnection.commit();
        } catch (Throwable ex) {
            throw new SQLException(ex);
        }
        // 重置连接的ConnectionContext
        context.reset();
    }

    private void processGlobalTransactionCommit() throws SQLException {
        try {
            // 向远程的TC中注册分支事务，并检查、增加全局行锁
            register();
        } catch (TransactionException e) {
            // 出现异常时，回滚本地事务 再重试。
            // 大多数情况是因为全局锁冲突走到这里。
            recognizeLockKeyConflictException(e, context.buildLockKeys());
        }
        try {
            // 回滚日志管理组件，持久化undo log
            UndoLogManagerFactory.getUndoLogManager(this.getDbType()).flushUndoLogs(this);
            // 提交本地事务
            targetConnection.commit();
        } catch (Throwable ex) {
            LOGGER.error("process connectionProxy commit error: {}", ex.getMessage(), ex);
            // 上报分支事务执行失败，用于监控
            report(false);
            throw new SQLException(ex);
        }
        // 上报分支事务执行成功，默认不会上报
        if (IS_REPORT_SUCCESS_ENABLE) {
            report(true);
        }
        // 重置连接的ConnectionContext
        context.reset();
    }

    private void register() throws TransactionException {
        if (!context.hasUndoLog() || !context.hasLockKey()) {
            return;
        }

        // 分支事务注册：将事务类型AT、资源ID（资源在前面的流程已经注册过了）、事务xid、全局锁keys作为分支事务信息注册到seata server
        Long branchId = DefaultResourceManager.get().branchRegister(BranchType.AT, getDataSourceProxy().getResourceId(),
                null, context.getXid(), context.getApplicationData(), context.buildLockKeys());
        context.setBranchId(branchId);
    }

    @Override
    public void rollback() throws SQLException {
        targetConnection.rollback();
        if (context.inGlobalTransaction() && context.isBranchRegistered()) {
            report(false);
        }
        context.reset();
    }

    /**
     * change connection autoCommit to false by seata
     *
     * @throws SQLException the sql exception
     */
    public void changeAutoCommit() throws SQLException {
        getContext().setAutoCommitChanged(true);
        setAutoCommit(false);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if ((context.inGlobalTransaction() || context.isGlobalLockRequire()) && autoCommit && !getAutoCommit()) {
            // change autocommit from false to true, we should commit() first according to JDBC spec.
            doCommit();
        }
        targetConnection.setAutoCommit(autoCommit);
    }

    private void report(boolean commitDone) throws SQLException {
        if (context.getBranchId() == null) {
            return;
        }
        int retry = REPORT_RETRY_COUNT;
        while (retry > 0) {
            try {
                DefaultResourceManager.get().branchReport(BranchType.AT, context.getXid(), context.getBranchId(),
                        commitDone ? BranchStatus.PhaseOne_Done : BranchStatus.PhaseOne_Failed, null);
                return;
            } catch (Throwable ex) {
                LOGGER.error("Failed to report [" + context.getBranchId() + "/" + context.getXid() + "] commit done ["
                        + commitDone + "] Retry Countdown: " + retry);
                retry--;

                if (retry == 0) {
                    throw new SQLException("Failed to report branch status " + commitDone, ex);
                }
            }
        }
    }

    public static class LockRetryPolicy {
        protected static final boolean LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT = ConfigurationFactory
                .getInstance().getBoolean(ConfigurationKeys.CLIENT_LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT, DEFAULT_CLIENT_LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT);

        protected final ConnectionProxy connection;

        public LockRetryPolicy(ConnectionProxy connection) {
            this.connection = connection;
        }

        public <T> T execute(Callable<T> callable) throws Exception {
            // the only case that not need to retry acquire lock hear is
            //    LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT == true && connection#autoCommit == true
            // because it has retry acquire lock when AbstractDMLBaseExecutor#executeAutoCommitTrue
            if (LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT && connection.getContext().isAutoCommitChanged()) {
                // 不需要重试获取全局锁的情况
                return callable.call();
            } else {
                // 需要重试获取全局锁的情况，默认
                // LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT == false
                // or LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT == true && autoCommit == false
                return doRetryOnLockConflict(callable);
            }
        }

        protected <T> T doRetryOnLockConflict(Callable<T> callable) throws Exception {
            LockRetryController lockRetryController = new LockRetryController();
            while (true) {
                try {
                    return callable.call();
                } catch (LockConflictException lockConflict) {
                    // 出现全局锁冲突，回滚本地事务
                    onException(lockConflict);
                    // AbstractDMLBaseExecutor#executeAutoCommitTrue the local lock is released
                    if (connection.getContext().isAutoCommitChanged()
                            && lockConflict.getCode() == TransactionExceptionCode.LockKeyConflictFailFast) {
                        lockConflict.setCode(TransactionExceptionCode.LockKeyConflict);
                    }
                    // 线程睡眠10ms，然后再重试，超过重试次数，抛出异常结束流程
                    lockRetryController.sleep(lockConflict);
                } catch (Exception e) {
                    // 出现非全局锁冲突的异常，则直接报错返回
                    onException(e);
                    throw e;
                }
            }
        }

        /**
         * Callback on exception in doLockRetryOnConflict.
         *
         * @param e invocation exception
         * @throws Exception error
         */
        protected void onException(Exception e) throws Exception {
        }
    }
}
