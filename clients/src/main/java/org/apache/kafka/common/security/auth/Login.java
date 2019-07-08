/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.security.auth;

import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

/**
 * Login interface for authentication.
 */
public interface Login {

    /**
     * Configures this login instance.
     * 配置Login对象
     */
    void configure(Map<String, ?> configs, String loginContextName);

    /**
     * Performs login for each login module specified for the login context of this instance.
     * 调用LoginModule的login()方法和commit()方法
     */
    LoginContext login() throws LoginException;

    /**
     * Returns the authenticated subject of this login context.
     * 返回PlainLoginModule中配置好的Subject对象
     */
    Subject subject();

    /**
     * Returns the service name to be used for SASL.
     * 返回服务名称，在DefaultLogin实现中始终返回"kafka"字符串
     */
    String serviceName();

    /**
     * Closes this instance.
     */
    void close();
}

