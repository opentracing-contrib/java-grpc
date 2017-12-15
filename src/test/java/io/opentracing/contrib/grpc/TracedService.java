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

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentracing.contrib.grpc.gen.GreeterGrpc;
import io.opentracing.contrib.grpc.gen.HelloReply;
import io.opentracing.contrib.grpc.gen.HelloRequest;
import java.io.IOException;

public class TracedService {

  private int port = 50051;
  private Server server;

  void start() throws IOException {
    server = ServerBuilder.forPort(port)
        .addService(new GreeterImpl())
        .build()
        .start();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        TracedService.this.stop();
      }
    });
  }

  void startWithInterceptor(ServerTracingInterceptor tracingInterceptor) throws IOException {

    server = ServerBuilder.forPort(port)
        .addService(tracingInterceptor.intercept(new GreeterImpl()))
        .build()
        .start();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        TracedService.this.stop();
      }
    });
  }

  void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  private class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello").build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();

    }
  }
}
