/*
 * Copyright 2017 The OpenTracing Authors
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

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentracing.contrib.grpc.gen.GreeterGrpc;
import io.opentracing.contrib.grpc.gen.HelloRequest;

public class TracedClient {

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  public TracedClient(String host, int port, ClientTracingInterceptor tracingInterceptor) {
    channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();

    if (tracingInterceptor == null) {
      blockingStub = GreeterGrpc.newBlockingStub(channel);
    } else {
      blockingStub = GreeterGrpc.newBlockingStub(tracingInterceptor.intercept(channel));
    }
  }

  void shutdown() throws InterruptedException {
    channel.shutdown();
  }

  boolean greet(String name) {
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    try {
      blockingStub.sayHello(request);
    } catch (Exception e) {
      return false;
    } finally {
      try {
        this.shutdown();
      } catch (Exception e) {
        return false;
      }
    }
    return true;

  }
}
