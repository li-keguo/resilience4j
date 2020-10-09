/*
 * Copyright 2020 Michael Pollind
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.micronaut.bulkhead;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.micronaut.BaseInterceptor;
import io.github.resilience4j.micronaut.ResilienceInterceptPhase;
import io.micronaut.aop.InterceptedMethod;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.MethodExecutionHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A {@link MethodInterceptor} that intercepts all method calls which are annotated with a {@link io.github.resilience4j.micronaut.annotation.Bulkhead}
 * annotation.
 **/
@Singleton
@Requires(beans = ThreadPoolBulkheadRegistry.class)
public class ThreadPoolBulkheadInterceptor extends BaseInterceptor implements MethodInterceptor<Object,Object> {
    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolBulkheadInterceptor.class);

    private final ThreadPoolBulkheadRegistry bulkheadRegistry;
    private final BeanContext beanContext;

    /**
     * @param beanContext      The bean context to allow for DI of class annotated with {@link javax.inject.Inject}.
     * @param bulkheadRegistry bulkhead registry used to retrieve {@link Bulkhead} by name
     */
    public ThreadPoolBulkheadInterceptor(BeanContext beanContext,
                                         ThreadPoolBulkheadRegistry bulkheadRegistry) {
        this.bulkheadRegistry = bulkheadRegistry;
        this.beanContext = beanContext;
    }

    @Override
    public int getOrder() {
        return ResilienceInterceptPhase.BULKHEAD.getPosition();
    }

    /**
     * Finds a fallback method for the given context.
     *
     * @param context The context
     * @return The fallback method if it is present
     */
    @Override
    public Optional<? extends MethodExecutionHandle<?, Object>> findFallbackMethod(MethodInvocationContext<Object, Object> context) {
        ExecutableMethod executableMethod = context.getExecutableMethod();
        final String fallbackMethod = executableMethod.stringValue(io.github.resilience4j.micronaut.annotation.Bulkhead.class, "fallbackMethod").orElse("");
        Class<?> declaringType = context.getDeclaringType();
        return beanContext.findExecutionHandle(declaringType, fallbackMethod, context.getArgumentTypes());
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        Optional<AnnotationValue<io.github.resilience4j.micronaut.annotation.Bulkhead>> opt = context.findAnnotation(io.github.resilience4j.micronaut.annotation.Bulkhead.class);
        if (!opt.isPresent()) {
            return context.proceed();
        }
        final io.github.resilience4j.micronaut.annotation.Bulkhead.Type type = opt.get().enumValue("type", io.github.resilience4j.micronaut.annotation.Bulkhead.Type.class).orElse(io.github.resilience4j.micronaut.annotation.Bulkhead.Type.SEMAPHORE);
        if (type != io.github.resilience4j.micronaut.annotation.Bulkhead.Type.THREADPOOL) {
            return context.proceed();
        }
        final String name = opt.get().stringValue().orElse("default");
        ThreadPoolBulkhead bulkhead = this.bulkheadRegistry.bulkhead(name);

        InterceptedMethod interceptedMethod = InterceptedMethod.of(context);
        if(interceptedMethod.resultType() == InterceptedMethod.ResultType.COMPLETION_STAGE){
            return this.fallbackForFuture(bulkhead.executeSupplier(() -> {
                try {
                    return ((CompletableFuture<?>) context.proceed()).get();
                } catch (Throwable e) {
                    throw new CompletionException(e);
                }
            }), context);
        }

        throw new IllegalStateException(
            "ThreadPool bulkhead is only applicable for completable futures");
    }
}