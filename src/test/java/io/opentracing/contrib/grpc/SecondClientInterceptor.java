/*
 * Copyright 2017-2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.grpc;

import static org.junit.Assert.assertNotNull;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentracing.mock.MockTracer;
import javax.annotation.Nullable;

public class SecondClientInterceptor implements ClientInterceptor {

    private final MockTracer tracer;

    public SecondClientInterceptor(MockTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {

        assertNotNull(tracer.activeSpan());

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                assertNotNull(tracer.activeSpan());
                delegate().start(new ForwardingClientCallListener
                    .SimpleForwardingClientCallListener<RespT>(responseListener) {}, headers);
            }

            @Override
            public void request(int numMessages) {
                assertNotNull(tracer.activeSpan());
                delegate().request(numMessages);
            }

            @Override
            public void cancel(@Nullable String message, @Nullable Throwable cause) {
                assertNotNull(tracer.activeSpan());
                delegate().cancel(message, cause);
            }

            @Override
            public void halfClose() {
                assertNotNull(tracer.activeSpan());
                delegate().halfClose();
            }

            @Override
            public void sendMessage(ReqT message) {
                assertNotNull(tracer.activeSpan());
                delegate().sendMessage(message);
            }

            @Override
            public boolean isReady() {
                assertNotNull(tracer.activeSpan());
                return delegate().isReady();
            }

            @Override
            public void setMessageCompression(boolean enabled) {
                assertNotNull(tracer.activeSpan());
                delegate().setMessageCompression(enabled);
            }

            @Override
            public Attributes getAttributes() {
                assertNotNull(tracer.activeSpan());
                return delegate().getAttributes();
            }
        };
    }
}
