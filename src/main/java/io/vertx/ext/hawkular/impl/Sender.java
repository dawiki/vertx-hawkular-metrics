/*
 * Copyright 2015 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.hawkular.impl;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.hawkular.VertxHawkularOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.concurrent.TimeUnit.*;
import static java.util.stream.Collectors.*;

/**
 * Sends collected metrics to the Hawkular server.
 *
 * @author Thomas Segismont
 */
public class Sender implements Handler<List<DataPoint>> {
  private static final Logger LOG = LoggerFactory.getLogger(Sender.class);

  private final Vertx vertx;
  private final String metricsURI;
  private final String tenant;
  private final int batchSize;
  private final long batchDelay;
  private final List<DataPoint> queue;

  private HttpClient httpClient;
  private long timerId;

  private long sendTime;

  /**
   * @param vertx   the {@link Vertx} managed instance
   * @param options Vertx Hawkular options
   * @param context the metric collection and sending execution context
   */
  public Sender(Vertx vertx, VertxHawkularOptions options, Context context) {
    this.vertx = vertx;
    metricsURI = options.getMetricsServiceUri() + "/metrics/data";
    tenant = options.getTenant();
    batchSize = options.getBatchSize();
    batchDelay = NANOSECONDS.convert(options.getBatchDelay(), SECONDS);
    queue = new ArrayList<>(batchSize);
    context.runOnContext(aVoid -> {
      HttpClientOptions httpClientOptions = options.getHttpOptions()
        .setDefaultHost(options.getHost())
        .setDefaultPort(options.getPort());
      httpClient = vertx.createHttpClient(httpClientOptions);
      timerId = vertx.setPeriodic(MILLISECONDS.convert(batchDelay, NANOSECONDS), this::flushIfIdle);
      }
    );
    sendTime = System.nanoTime();
  }

  @Override
  public void handle(List<DataPoint> dataPoints) {
    if (LOG.isTraceEnabled()) {
      String lineSeparator = System.getProperty("line.separator");
      String msg = "Handling data points: " + lineSeparator +
        dataPoints.stream().map(DataPoint::toString).collect(joining(lineSeparator));
      LOG.trace(msg);
    }

    if (queue.size() + dataPoints.size() < batchSize) {
      queue.addAll(dataPoints);
      return;
    }
    List<DataPoint> temp = new ArrayList<>(queue.size() + dataPoints.size());
    temp.addAll(queue);
    temp.addAll(dataPoints);
    queue.clear();
    do {
      List<DataPoint> subList = temp.subList(0, batchSize);
      send(subList);
      subList.clear();
    } while (temp.size() >= batchSize);
    queue.addAll(temp);
  }

  private void send(List<DataPoint> dataPoints) {
    JsonObject mixedData = toHawkularMixedData(dataPoints);
    httpClient.post(metricsURI, this::onResponse)
      .putHeader("Content-Type", "application/json")
      .putHeader("Hawkular-Tenant", tenant)
      .exceptionHandler(err -> LOG.trace("Could not send metrics", err))
      .end(mixedData.encode(), "UTF-8");
    sendTime = System.nanoTime();
  }

  private JsonObject toHawkularMixedData(List<DataPoint> dataPoints) {
    JsonArray gauges = new JsonArray();
    JsonArray counters = new JsonArray();

    dataPoints.forEach(metric -> {

      if (metric instanceof GaugePoint) {
        GaugePoint gaugePoint = (GaugePoint) metric;

        JsonObject point = new JsonObject();
        point.put("timestamp", gaugePoint.getTimestamp());
        point.put("value", gaugePoint.getValue());

        JsonObject gauge = new JsonObject();
        gauge.put("id", gaugePoint.getName());
        gauge.put("data", new JsonArray(Collections.singletonList(point)));

        gauges.add(gauge);
      }

      if (metric instanceof CounterPoint) {
        CounterPoint counterPoint = (CounterPoint) metric;

        JsonObject point = new JsonObject();
        point.put("timestamp", counterPoint.getTimestamp());
        point.put("value", counterPoint.getValue());

        JsonObject counter = new JsonObject();
        counter.put("id", counterPoint.getName());
        counter.put("data", new JsonArray(Collections.singletonList(point)));

        counters.add(counter);
      }

    });

    JsonObject mixedData = new JsonObject();
    if (!gauges.isEmpty()) {
      mixedData.put("gauges", gauges);
    }
    if (!counters.isEmpty()) {
      mixedData.put("counters", counters);
    }
    return mixedData;
  }

  private void onResponse(HttpClientResponse response) {
    if (response.statusCode() != 200 && LOG.isTraceEnabled()) {
      response.bodyHandler(msg -> {
        LOG.trace("Could not send metrics: " + response.statusCode() + " : " + msg.toString());
      });
    }
  }

  private void flushIfIdle(Long timerId) {
    if (System.nanoTime() - sendTime > batchDelay && !queue.isEmpty()) {
      send(queue);
      queue.clear();
    }
  }

  public void stop() {
    vertx.cancelTimer(timerId);
    httpClient.close();
  }
}
