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
package brooklyn.rest.resources;

import static com.google.common.collect.Iterables.transform;

import java.util.List;
import java.util.Map;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.management.entitlement.Entitlements;
import brooklyn.rest.api.EntityConfigApi;
import brooklyn.rest.domain.EntityConfigSummary;
import brooklyn.rest.transform.EntityTransformer;
import brooklyn.rest.util.WebResourceUtils;
import brooklyn.util.flags.TypeCoercions;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class EntityConfigResource extends AbstractBrooklynRestResource implements EntityConfigApi {

  @Override
  public List<EntityConfigSummary> list(final String application, final String entityToken) {
    final EntityLocal entity = brooklyn().getEntity(application, entityToken);

    return Lists.newArrayList(transform(
        entity.getEntityType().getConfigKeys(),
        new Function<ConfigKey<?>, EntityConfigSummary>() {
          @Override
          public EntityConfigSummary apply(ConfigKey<?> config) {
            return EntityTransformer.entityConfigSummary(entity, config);
          }
        }));
  }

  // TODO support parameters  ?show=value,summary&name=xxx &format={string,json,xml}
  // (and in sensors class)
  @Override
  public Map<String, Object> batchConfigRead(String application, String entityToken) {
    // TODO: add test
    EntityLocal entity = brooklyn().getEntity(application, entityToken);
    Map<ConfigKey<?>, Object> source = ((EntityInternal)entity).getAllConfig();
    Map<String, Object> result = Maps.newLinkedHashMap();
    for (Map.Entry<ConfigKey<?>, Object> ek: source.entrySet()) {
        result.put(ek.getKey().getName(), getValueForDisplay(ek.getValue(), true, false));
    }
    return result;
  }

  @Override
  public Object get(String application, String entityToken, String configKeyName) {
      return get(true, application, entityToken, configKeyName);
  }
  
  @Override
  public String getPlain(String application, String entityToken, String configKeyName) {
      return (String)get(true, application, entityToken, configKeyName);
  }
  
  public Object get(boolean preferJson, String application, String entityToken, String configKeyName) {
    EntityLocal entity = brooklyn().getEntity(application, entityToken);
    ConfigKey<?> ck = findConfig(entity, configKeyName);
    
    return getValueForDisplay(entity.getConfigRaw(ck, true).orNull(), preferJson, true);
  }

  private ConfigKey<?> findConfig(EntityLocal entity, String configKeyName) {
      ConfigKey<?> ck = entity.getEntityType().getConfigKey(configKeyName);
      if (ck==null) ck = new BasicConfigKey<Object>(Object.class, configKeyName);
      return ck;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Override
  public void set(String application, String entityToken, String configName, Boolean recurse, Object newValue) {
      final EntityLocal entity = brooklyn().getEntity(application, entityToken);
      if (!Entitlements.isEntitled(mgmt().getEntitlementManager(), Entitlements.MODIFY_ENTITY, entity)) {
          throw WebResourceUtils.unauthorized("User '%s' is not authorized to modify entity '%s'",
              Entitlements.getEntitlementContext().user(), entity);
      }

      ConfigKey ck = findConfig(entity, configName);
      ((EntityInternal)entity).setConfig(ck, TypeCoercions.coerce(newValue, ck.getType()));
      if (Boolean.TRUE.equals(recurse)) {
          for (Entity e2: Entities.descendants(entity, Predicates.alwaysTrue(), false)) {
              ((EntityInternal)e2).setConfig(ck, newValue);
          }
      }
  }

}
