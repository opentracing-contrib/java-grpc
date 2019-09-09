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

import com.google.common.collect.ImmutableMap;
import io.opentracing.Span;
import io.opentracing.log.Fields;

final class GrpcFields {

  static final String ERROR = "error";
  static final String HEADERS = "headers";

  static final String CLIENT_CALL_START = "client-call-start";
  static final String CLIENT_CALL_CANCEL = "client-call-cancel";
  static final String CLIENT_CALL_HALF_CLOSE = "client-call-half-close";
  static final String CLIENT_CALL_SEND_MESSAGE = "client-call-send-message";

  static final String CLIENT_CALL_LISTENER_ON_HEADERS = "client-call-listener-on-headers";
  static final String CLIENT_CALL_LISTENER_ON_MESSAGE = "client-call-listener-on-message";
  static final String CLIENT_CALL_LISTENER_ON_CLOSE = "client-call-listener-on-close";

  static final String SERVER_CALL_SEND_HEADERS = "server-call-send-headers";
  static final String SERVER_CALL_SEND_MESSAGE = "server-call-send-message";
  static final String SERVER_CALL_CLOSE = "server-call-close";

  static final String SERVER_CALL_LISTENER_ON_MESSAGE = "server-call-listener-on-message";
  static final String SERVER_CALL_LISTENER_ON_HALF_CLOSE = "server-call-listener-on-half-close";
  static final String SERVER_CALL_LISTENER_ON_CANCEL = "server-call-listener-on-cancel";
  static final String SERVER_CALL_LISTENER_ON_COMPLETE = "server-call-listener-on-complete";

  static void logClientCallError(Span span, String message, Throwable cause) {
    logCallError(span, message, cause, "Client");
  }

  static void logServerCallError(Span span, String message, Throwable cause) {
    logCallError(span, message, cause, "Server");
  }

  private static void logCallError(Span span, String message, Throwable cause, String name) {
    ImmutableMap.Builder<String, Object> builder =
        ImmutableMap.<String, Object>builder().put(Fields.EVENT, GrpcFields.ERROR);
    String causeMessage = null;
    if (cause != null) {
      builder.put(Fields.ERROR_OBJECT, cause);
      causeMessage = cause.getMessage();
    }
    if (message != null) {
      builder.put(Fields.MESSAGE, message);
    } else if (causeMessage != null) {
      builder.put(Fields.MESSAGE, causeMessage);
    } else {
      builder.put(Fields.MESSAGE, name + " call failed");
    }
    span.log(builder.build());
  }
}
