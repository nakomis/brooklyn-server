/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.camp.brooklyn.spi.dsl;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.javalang.Reflections;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;

public class DslDeferredFunctionCall extends BrooklynDslDeferredSupplier<Object> {

    private static final long serialVersionUID = 3243262633795112155L;

    // TODO should this be some of the super types?
    private BrooklynDslDeferredSupplier<?> object;
    private String fnName;
    private List<?> args;

    public DslDeferredFunctionCall(BrooklynDslDeferredSupplier<?> o, String fn, List<Object> args) {
        this.object = o;
        this.fnName = fn;
        this.args = args;
    }

    @Override
    public Maybe<Object> getImmediately() {
        Maybe<?> obj = object.getImmediately();
        if (obj.isPresent()) {
            if (obj.isNull()) {
                throw new IllegalArgumentException("Deferred function call, " + object + 
                        " evaluates to null (when calling " + fnName + "(" + toString(args) + "))");
            }
            return Maybe.of(invokeOn(obj.get()));
        }
        return Maybe.absent("Could not evaluate immediately " + object);
    }

    @Override
    public Task<Object> newTask() {
        return Tasks.builder()
                .displayName("Deferred function call " + object + "." + fnName + "(" + toString(args) + ")")
                .tag(BrooklynTaskTags.TRANSIENT_TASK_TAG)
                .dynamic(false)
                .body(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        Object obj = object.get();
                        if (obj == null) {
                            throw new IllegalArgumentException("Deferred function call, " + object + 
                                    " evaluates to null (when calling " + fnName + "(" + DslDeferredFunctionCall.toString(args) + "))");
                        }
                        return invokeOn(obj);
                    }

                }).build();
    }

    protected Object invokeOn(Object obj) {
        return invokeOn(obj, fnName, args);
    }

    protected static Object invokeOn(Object obj, String fnName, List<?> args) {
        Maybe<Object> v;
        try {
            v = Reflections.invokeMethodFromArgs(obj, fnName, args);
        } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
            Exceptions.propagateIfFatal(e);
            throw Exceptions.propagate(new InvocationTargetException(e, "Error invoking '"+fnName+"("+toString(args)+")' on '"+obj+"'"));
        }
        if (v.isPresent()) {
            // Value is most likely another BrooklynDslDeferredSupplier - let the caller handle it,
            return v.get();
        } else {
            throw new IllegalArgumentException("No such function '"+fnName+"("+toString(args)+")' on "+obj);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(object, fnName, args);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DslDeferredFunctionCall that = DslDeferredFunctionCall.class.cast(obj);
        return Objects.equal(this.object, that.object) &&
                Objects.equal(this.fnName, that.fnName) &&
                Objects.equal(this.args, that.args);
    }

    @Override
    public String toString() {
        return object + "." + fnName + "(" + toString(args) + ")";
    }
    
    private static String toString(List<?> args) {
        if (args == null) return "";
        return Joiner.on(", ").join(args);
    }
}
