/*
 * Copyright 2017-2020 The OpenTracing Authors
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

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentracing.mock.MockTracer;

public class SecondServerInterceptor implements ServerInterceptor {

  private final MockTracer tracer;

  SecondServerInterceptor(MockTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public <ReqT, RespT> Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

    assertNotNull(tracer.activeSpan());

    call =
        new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {

          @Override
          public void sendHeaders(Metadata headers) {
            assertNotNull(tracer.activeSpan());
            super.sendHeaders(headers);
          }

          @Override
          public void sendMessage(RespT message) {
            assertNotNull(tracer.activeSpan());
            super.sendMessage(message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            assertNotNull(tracer.activeSpan());
            super.close(status, trailers);
          }
        };

    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
        next.startCall(call, headers)) {

      @Override
      public void onReady() {
        assertNotNull(tracer.activeSpan());
        super.onReady();
      }

      @Override
      public void onMessage(ReqT message) {
        assertNotNull(tracer.activeSpan());
        super.onMessage(message);
      }

      @Override
      public void onHalfClose() {
        assertNotNull(tracer.activeSpan());
        super.onHalfClose();
      }

      @Override
      public void onCancel() {
        assertNotNull(tracer.activeSpan());
        super.onCancel();
      }

      @Override
      public void onComplete() {
        assertNotNull(tracer.activeSpan());
        super.onComplete();
      }
    };
  }
}
