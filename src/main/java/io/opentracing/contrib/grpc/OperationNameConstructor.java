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

import io.grpc.MethodDescriptor;

/** Interface that allows span operation names to be constructed from an RPC's method descriptor. */
public interface OperationNameConstructor {

  /**
   * Default span operation name constructor, that will return an RPC's method name when
   * constructOperationName is called.
   */
  OperationNameConstructor DEFAULT =
      new OperationNameConstructor() {
        @Override
        public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
          return method.getFullMethodName();
        }
      };

  /**
   * Constructs a span's operation name from the RPC's method.
   *
   * @param method the rpc method to extract a name from
   * @param <ReqT> the rpc request type
   * @param <RespT> the rpc response type
   * @return the operation name
   */
  <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method);
}
