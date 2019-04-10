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

import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.opentracing.contrib.grpc.gen.GreeterGrpc;
import io.opentracing.contrib.grpc.gen.HelloReply;
import io.opentracing.contrib.grpc.gen.HelloRequest;
import java.util.concurrent.TimeUnit;

class TracedClient {

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  TracedClient(ManagedChannel channel, ClientInterceptor... interceptors) {
    blockingStub = GreeterGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptors));
  }

  TracedClient(
      ManagedChannel channel,
      long deadline,
      String compression,
      ClientInterceptor... interceptors) {
    blockingStub = GreeterGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptors))
        .withDeadlineAfter(deadline, TimeUnit.MILLISECONDS)
        .withCompression(compression);
  }

  HelloReply greet(String name) {
    try {
      return blockingStub.sayHello(HelloRequest.newBuilder().setName(name).build());
    } catch (Exception ignored) {
      return null;
    }
  }
}
