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
package io.seata.tm.api.transaction;

import io.seata.tm.api.TransactionalExecutor;

/**
 * Propagation level of global transactions.
 * <p>
 * 全局事务传播级别
 *
 * @author haozhibei
 * @see io.seata.spring.annotation .GlobalTransactional#propagation() // TM annotation
 * @see io.seata.spring.annotation .GlobalTransactionalInterceptor#invoke(MethodInvocation) // the interceptor of TM
 * @see io.seata.tm.api.TransactionalTemplate#execute(TransactionalExecutor) // the transaction template of TM
 */
public enum Propagation {
    /**
     * The REQUIRED.
     * 如果全局事务已存在，则假如当前事务，作为一个分支事务；如果全局事务不存在，则开启一个全新的全局事务
     * The default propagation.
     *
     * <p>
     * If transaction is existing, execute with current transaction,
     * else execute with new transaction.
     * </p>
     *
     * <p>
     * The logic is similar to the following code:
     * <code><pre>
     *     if (tx == null) {
     *         try {
     *             tx = beginNewTransaction(); // begin new transaction, is not existing
     *             Object rs = business.execute(); // execute with new transaction
     *             commitTransaction(tx);
     *             return rs;
     *         } catch (Exception ex) {
     *             rollbackTransaction(tx);
     *             throw ex;
     *         }
     *     } else {
     *         return business.execute(); // execute with current transaction
     *     }
     * </pre></code>
     * </p>
     */
    REQUIRED,

    /**
     * The REQUIRES_NEW.
     * 如果全局事务已经存在，则暂停它，用新的事务来执行当前业务逻辑
     *
     * <p>
     * If transaction is existing, suspend it, and then execute business with new transaction.
     * </p>
     *
     * <p>
     * The logic is similar to the following code:
     * <code><pre>
     *     try {
     *         if (tx != null) {
     *             suspendedResource = suspendTransaction(tx); // suspend current transaction
     *         }
     *         try {
     *             tx = beginNewTransaction(); // begin new transaction
     *             Object rs = business.execute(); // execute with new transaction
     *             commitTransaction(tx);
     *             return rs;
     *         } catch (Exception ex) {
     *             rollbackTransaction(tx);
     *             throw ex;
     *         }
     *     } finally {
     *         if (suspendedResource != null) {
     *             resumeTransaction(suspendedResource); // resume transaction
     *         }
     *     }
     * </pre></code>
     * </p>
     */
    REQUIRES_NEW,

    /**
     * The NOT_SUPPORTED.
     *
     * 如果全局事务已经存在，则暂停它，用非事务的方式执行
     *
     * <p>
     * If transaction is existing, suspend it, and then execute business without transaction.
     * </p>
     *
     * <p>
     * The logic is similar to the following code:
     * <code><pre>
     *     try {
     *         if (tx != null) {
     *             suspendedResource = suspendTransaction(tx); // suspend current transaction
     *         }
     *         return business.execute(); // execute without transaction
     *     } finally {
     *         if (suspendedResource != null) {
     *             resumeTransaction(suspendedResource); // resume transaction
     *         }
     *     }
     * </pre></code>
     * </p>
     */
    NOT_SUPPORTED,

    /**
     * The SUPPORTS.
     * 如果全局事务不存在，则以非事务方式运行它；如果全局事务存在，则加入当前事务；
     *
     * <p>
     * If transaction is not existing, execute without global transaction,
     * else execute business with current transaction.
     * </p>
     *
     * <p>
     * The logic is similar to the following code:
     * <code><pre>
     *     if (tx != null) {
     *         return business.execute(); // execute with current transaction
     *     } else {
     *         return business.execute(); // execute without transaction
     *     }
     * </pre></code>
     * </p>
     */
    SUPPORTS,

    /**
     * The NEVER.
     * 存在全局事务，抛出异常；不存在全局事务，则以非事务的方式执行
     *
     * <p>
     * If transaction is existing, throw exception,
     * else execute business without transaction.
     * </p>
     *
     * <p>
     * The logic is similar to the following code:
     * <code><pre>
     *     if (tx != null) {
     *         throw new TransactionException("existing transaction");
     *     }
     *     return business.execute(); // execute without transaction
     * </pre></code>
     * </p>
     */
    NEVER,

    /**
     * The MANDATORY.
     * 如果全局事务不存在，则抛出异常；如果全局事务存在，则加入当前事务；
     *
     * <p>
     * If transaction is not existing, throw exception,
     * else execute business with current transaction.
     * </p>
     *
     * <p>
     * The logic is similar to the following code:
     * <code><pre>
     *     if (tx == null) {
     *         throw new TransactionException("not existing transaction");
     *     }
     *     return business.execute(); // execute with current transaction
     * </pre></code>
     * </p>
     */
    MANDATORY
}
