/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.core.mgmt.internal;

/**
 * Indicates that there is an id conflict (e.g. when creating an app using a pre-defined id,
 * using {@code PUT /applications/abcdefghijklm}).
 */
public class IdAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = -602477310528752776L;

    public IdAlreadyExistsException(String msg) {
        super(msg);
    }
    
    public IdAlreadyExistsException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
