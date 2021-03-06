/*
 * Copyright 2015 Victor Albertos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rx_cache.internal;


import com.google.common.annotations.VisibleForTesting;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import javax.inject.Inject;

import io.rx_cache.InvalidatorDynamicKey;
import io.rx_cache.Record;
import io.rx_cache.Reply;
import io.rx_cache.Source;
import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;

final class ProxyProviders implements InvocationHandler {
    private final ProxyTranslator proxyTranslator;
    private final Cache cache;
    private final Boolean useExpiredDataIfLoaderNotAvailable;

    @Inject public ProxyProviders(ProxyTranslator proxyTranslator, Cache cache, Boolean useExpiredDataIfLoaderNotAvailable) {
        this.proxyTranslator = proxyTranslator;
        this.cache = cache;
        this.useExpiredDataIfLoaderNotAvailable = useExpiredDataIfLoaderNotAvailable;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final ProxyTranslator.ConfigProvider configProvider = proxyTranslator.processMethod(method, args);
        return getMethodImplementation(configProvider);
    }

    @VisibleForTesting Observable<Object> getMethodImplementation(final ProxyTranslator.ConfigProvider configProvider) {
        return Observable.defer(new Func0<Observable<Object>>() {
            @Override public Observable<Object> call() {
                return getData(configProvider);
            }
        });
    }

    private Observable<Object> getData(final ProxyTranslator.ConfigProvider configProvider) {
        return Observable.just(cache.retrieve(configProvider.getKey(), configProvider.getDynamicKey(), useExpiredDataIfLoaderNotAvailable))
                .map(new Func1<Record, Observable<Reply>>() {
                    @Override public Observable<Reply> call(final Record record) {
                        if (record != null && !configProvider.invalidator().invalidate())
                            return Observable.just(new Reply(record.getData(), record.getSource()));

                        return getDataFromLoader(configProvider, record);
                    }
                }).flatMap(new Func1<Observable<Reply>, Observable<Object>>() {
                    @Override public Observable<Object> call(Observable<Reply> responseObservable) {
                        return responseObservable.map(new Func1<Reply, Object>() {
                            @Override public Object call(Reply reply) {
                                return getReturnType(configProvider, reply);
                            }
                        });
                    }
                });
    }

    private Observable<Reply> getDataFromLoader(final ProxyTranslator.ConfigProvider configProvider, final Record record) {
        return configProvider.getLoaderObservable().map(new Func1() {
            @Override public Reply call(Object data) {
                if (data == null && useExpiredDataIfLoaderNotAvailable && record != null) {
                    return new Reply(record.getData(), record.getSource());
                }

                if (configProvider.invalidator() instanceof InvalidatorDynamicKey) {
                    InvalidatorDynamicKey invalidatorDynamicKey = (InvalidatorDynamicKey) configProvider.invalidator();
                    if (invalidatorDynamicKey.invalidate())
                        cache.clearDynamicKey(configProvider.getKey(), invalidatorDynamicKey.dynamicKey().toString());
                } else if (configProvider.invalidator().invalidate()) {
                    cache.clear(configProvider.getKey());
                }

                if (data == null)
                    throw new RuntimeException(Locale.NOT_DATA_RETURN_WHEN_CALLING_OBSERVABLE_LOADER + " " + configProvider.getKey());

                cache.save(configProvider.getKey(), configProvider.getDynamicKey(), data, configProvider.getLifeTimeMillis());
                return new Reply(data, Source.CLOUD);
            }
        });
    }

    private Object getReturnType(ProxyTranslator.ConfigProvider configProvider, Reply reply) {
        if (configProvider.requiredDetailedResponse()) {
            return reply;
        } else {
            return reply.getData();
        }
    }
}