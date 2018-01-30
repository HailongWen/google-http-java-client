/*
 * Copyright (c) 2018 Google Inc.
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

package com.google.api.client.util;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import com.google.common.annotations.VisibleForTesting;

import io.opencensus.contrib.http.util.HttpPropagationUtil;
import io.opencensus.trace.BlankSpan;
import io.opencensus.trace.EndSpanOptions;
import io.opencensus.trace.NetworkEvent;
import io.opencensus.trace.NetworkEvent.Type;
import io.opencensus.trace.Span;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.propagation.TextFormat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Utilities for Census monitoring and tracing.
 *
 * @since 1.24
 * @author Hailong Wen
 */
public class OpenCensusUtils {

  private static final Logger LOGGER = Logger.getLogger(OpenCensusUtils.class.getName());

  /**
   * OpenCensus tracing component.
   * When no OpenCensus implementation is provided, it will return a no-op tracer.
   */
  private static volatile Tracer tracer = Tracing.getTracer();

  /**
   * {@link TextFormat} used in tracing context propagation.
   */
  @Nullable
  @VisibleForTesting
  static volatile TextFormat propagationTextFormat = null;

  /**
   * {@link TextFormat.Setter} for {@link #propagationTextFormat}.
   */
  @Nullable
  @VisibleForTesting
  static volatile TextFormat.Setter propagationTextFormatSetter = null;

  private static AtomicLong idGenerator = new AtomicLong();

  /**
   * Sets the {@link TextFormat} used in context propagation.
   * @param textFormat the text format.
   */
  @VisibleForTesting
  static void setPropagationTextFormat(@Nullable TextFormat textFormat) {
    propagationTextFormat = textFormat;
  }

  /**
   * Sets the {@link TextFormat.Setter} used in context propagation.
   * @param textFormatSetter the {@code TextFormat.Setter} for the text format.
   */
  @VisibleForTesting
  static void setPropagationTextFormatSetter(@Nullable TextFormat.Setter textFormatSetter) {
    propagationTextFormatSetter = textFormatSetter;
  }

  /**
   * Returns the tracing component of OpenCensus.
   *
   * @return the tracing component of OpenCensus.
   */
  public static Tracer getTracer() {
    return tracer;
  }

  /**
   * Propagate information of current tracing context. This information will be injected into HTTP
   * header.
   *
   * @param span the span to be propagated.
   * @param headers the headers used in propagation.
   */
  public static void propagateTracingContext(Span span, HttpHeaders headers) {
    Preconditions.checkArgument(span != null, "span should not be null.");
    Preconditions.checkArgument(headers != null, "headers should not be null.");
    if (propagationTextFormat != null && propagationTextFormatSetter != null) {
      if (!span.equals(BlankSpan.INSTANCE)) {
        propagationTextFormat.inject(span.getContext(), headers, propagationTextFormatSetter);
      }
    }
  }

  /**
   * Returns an {@link EndSpanOptions} to end a http span according to the status code.
   *
   * @param statusCode the status code, can be null to represent no valid response is returned.
   * @return an {@code EndSpanOptions} that best suits the status code.
   */
  public static EndSpanOptions getEndSpanOptions(@Nullable Integer statusCode) {
    // Always sample the span, but optionally export it.
    EndSpanOptions.Builder builder = EndSpanOptions.builder();
    if (statusCode == null) {
      builder.setStatus(Status.UNKNOWN);
    } else if (!HttpStatusCodes.isSuccess(statusCode)) {
      switch (statusCode) {
        case HttpStatusCodes.STATUS_CODE_BAD_REQUEST:
          builder.setStatus(Status.INVALID_ARGUMENT);
          break;
        case HttpStatusCodes.STATUS_CODE_UNAUTHORIZED:
          builder.setStatus(Status.UNAUTHENTICATED);
          break;
        case HttpStatusCodes.STATUS_CODE_FORBIDDEN:
          builder.setStatus(Status.PERMISSION_DENIED);
          break;
        case HttpStatusCodes.STATUS_CODE_NOT_FOUND:
          builder.setStatus(Status.NOT_FOUND);
          break;
        case HttpStatusCodes.STATUS_CODE_PRECONDITION_FAILED:
          builder.setStatus(Status.FAILED_PRECONDITION);
          break;
        case HttpStatusCodes.STATUS_CODE_SERVER_ERROR:
          builder.setStatus(Status.UNAVAILABLE);
          break;
        default:
          builder.setStatus(Status.UNKNOWN);
      }
    } else {
      builder.setStatus(Status.OK);
    }
    return builder.build();
  }

  /**
   * Log a new message event which contains the size of the request.
   *
   * @param span The {@code span} in which the send event occurs.
   * @param size Size of the request.
   */
  public static void logSentMessageEvent(Span span, long size) {
    logMessageEvent(span, size, Type.SENT);
  }
  /**
   * Log a new message event which contains the size of the response.
   *
   * @param span The {@code span} in which the receive event occurs.
   */
  public static void logReceivedMessageEvent(Span span, long size) {
    logMessageEvent(span, size, Type.RECV);
  }

  /**
   * Log a message event. Currently use the {@link NetworkEvent} API.
   */
  @VisibleForTesting
  static void logMessageEvent(Span span, long size, Type eventType) {
    Preconditions.checkArgument(span != null, "span should not be null.");
    NetworkEvent event = NetworkEvent
        .builder(eventType, idGenerator.getAndIncrement())
        .setUncompressedMessageSize(size)
        .build();
    span.addNetworkEvent(event);
  }

  static {
    try {
      propagationTextFormat = HttpPropagationUtil.getCloudTraceFormat();
      propagationTextFormatSetter = new TextFormat.Setter<HttpHeaders>() {
        @Override
        public void put(HttpHeaders carrier, String key, String value) {
          carrier.set(key, value);
        }
      };
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Cannot initialize OpenCensus modules, tracing disabled.", e);
    }
  }

  private OpenCensusUtils() {}
}
