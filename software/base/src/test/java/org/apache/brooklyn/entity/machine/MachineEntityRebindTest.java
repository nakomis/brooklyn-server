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
package org.apache.brooklyn.entity.machine;

import static org.testng.Assert.assertTrue;

import java.util.List;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.mgmt.rebind.RebindTestFixtureWithApp;
import org.apache.brooklyn.core.objs.proxy.InternalFactory;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.LogWatcher;
import org.apache.brooklyn.util.core.internal.ssh.RecordingSshTool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;

import ch.qos.logback.classic.spi.ILoggingEvent;

public class MachineEntityRebindTest extends RebindTestFixtureWithApp {

    private SshMachineLocation origMachine;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        
        origMachine = mgmt().getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", "localhost")
                .configure(SshMachineLocation.SSH_TOOL_CLASS, RecordingSshTool.class.getName()));
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            RecordingSshTool.clear();
        }
    }
    
    @Test
    public void testRebindToMachineEntity() throws Exception {
        MachineEntity entity = origApp.createAndManageChild(EntitySpec.create(MachineEntity.class)
                .configure(BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, true));
        origApp.start(ImmutableList.of(origMachine));
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        
        rebind();
        
        Entity newEntity = mgmt().getEntityManager().getEntity(entity.getId());
        EntityAsserts.assertAttributeEqualsEventually(newEntity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
    }
    
    @Test
    public void testNoLogWarningsWhenRebindToMachineEntity() throws Exception {
        String loggerName = InternalFactory.class.getName();
        ch.qos.logback.classic.Level logLevel = ch.qos.logback.classic.Level.WARN;
        Predicate<ILoggingEvent> filter = Predicates.alwaysTrue();
        LogWatcher watcher = new LogWatcher(loggerName, logLevel, filter);

        watcher.start();
        try {
            origApp.createAndManageChild(EntitySpec.create(MachineEntity.class)
                    .configure(BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, true));
            origApp.start(ImmutableList.of(origMachine));
            
            rebind();
        
            List<ILoggingEvent> events = watcher.getEvents();
            assertTrue(events.isEmpty(), "events="+events);
            
        } finally {
            watcher.close();
        }
    }
}
