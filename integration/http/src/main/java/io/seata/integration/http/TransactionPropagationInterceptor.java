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
package io.seata.integration.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.seata.common.util.StringUtils;
import io.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;


/**
 * Springmvc Intercepter.
 *
 * @author wangxb
 */
public class TransactionPropagationInterceptor extends HandlerInterceptorAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionPropagationInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // HTTP请求执行前，从线程本地变量副本从获取xid
        String xid = RootContext.getXID();
        // 是否上游请求的请求头中包含rpcXid
        String rpcXid = request.getHeader(RootContext.KEY_XID);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("xid in RootContext[{}] xid in HttpContext[{}]", xid, rpcXid);
        }
        // 如果上游请求的请求头中包含xid，线程本地变量中不包含xid，则将rpcXid绑定到线程本地变量中
        if (StringUtils.isBlank(xid) && StringUtils.isNotBlank(rpcXid)) {
            RootContext.bind(rpcXid);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("bind[{}] to RootContext", rpcXid);
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        if (RootContext.inGlobalTransaction()) {
            XidResource.cleanXid(request.getHeader(RootContext.KEY_XID));
        }
    }

}
