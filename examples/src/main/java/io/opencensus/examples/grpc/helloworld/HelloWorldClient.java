/*
 * Copyright 2018, OpenCensus Authors
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

package io.opencensus.examples.grpc.helloworld;

import static io.opencensus.examples.grpc.helloworld.HelloWorldUtils.getPortOrDefaultFromArgs;
import static io.opencensus.examples.grpc.helloworld.HelloWorldUtils.getStringOrDefaultFromArgs;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.opencensus.common.Duration;
import io.opencensus.common.Scope;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.contrib.zpages.ZPageHandlers;
import io.opencensus.exporter.stats.prometheus.PrometheusStatsCollector;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsConfiguration;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.stats.Aggregation;
// import io.opencensus.stats.Distribution;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.Stats;
import io.opencensus.stats.View;
import io.opencensus.stats.View.Name;
import io.opencensus.stats.ViewManager;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.trace.SpanBuilder;
import io.opencensus.trace.Status.CanonicalCode;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A simple client that requests a greeting from the {@link HelloWorldServer}. */
public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private static final Tracer tracer = Tracing.getTracer();

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClient(String host, int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
            // needing certificates.
            .usePlaintext(true)
            .build());
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  HelloWorldClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Say hello to server. */
  public void greet(String name) {
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;

    SpanBuilder spanBuilder =
        tracer.spanBuilder("client").setRecordEvents(true).setSampler(Samplers.alwaysSample());
    try (Scope scope = spanBuilder.startScopedSpan()) {
      tracer.getCurrentSpan().addAnnotation("Saying Hello to Server.");
      response = blockingStub.sayHello(request);
      tracer.getCurrentSpan().addAnnotation("Received response from Server.");
    } catch (StatusRuntimeException e) {
      tracer
          .getCurrentSpan()
          .setStatus(
              CanonicalCode.valueOf(e.getStatus().getCode().name())
                  .toStatus()
                  .withDescription(e.getMessage()));
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Greeting: " + response.getMessage());
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    // Add final keyword to pass checkStyle.
    final String user = getStringOrDefaultFromArgs(args, 0, "world");
    final String host = getStringOrDefaultFromArgs(args, 1, "localhost");
    final int serverPort = getPortOrDefaultFromArgs(args, 2, 50051);
    final String cloudProjectId = getStringOrDefaultFromArgs(args, 3, null);
    final int zPagePort = getPortOrDefaultFromArgs(args, 4, 3001);

    // Registers all RPC views. For demonstration all views are registered. You may want to
    // start with registering basic views and register other views as needed for your application.
    RpcViews.registerAllViews();

    // Starts a HTTP server and registers all Zpages to it.
    ZPageHandlers.startHttpServerAndRegisterAll(zPagePort);
    logger.info("ZPages server starts at localhost:" + zPagePort);

    // Registers logging trace exporter.
    // LoggingTraceExporter.register();

    Tagger tagger = Tags.getTagger();
    TagKey JOB_ID_KEY = TagKey.create("job_id");
    TagValue jobIdValue = TagValue.create("2019-06-06-12345657");
    TagContext tctx = tagger.emptyBuilder().put(JOB_ID_KEY, jobIdValue).build();

    /*
    View latencyByJobId = View.create(
        Name.create("grpc.io/client/roundtrip_latency_by_job_id"),
        "client latency by job ID",
        RpcMeasureConstants.RPC_CLIENT_ROUNDTRIP_LATENCY,
        Aggregation.Distribution.create(bucketBoundaries),  // define your own histogram bucket boundaries
        Arrays.asList(RpcMeasureConstants.RPC_METHOD, JOB_ID_KEY));*/
    List<Double> boundaries = Arrays.asList(1.0, 10.0, 100.0, 1000.0);
    BucketBoundaries bucketBoundaries = BucketBoundaries.create(boundaries);

    View latencyByJobId =
        View.create(
            Name.create("ajamato_roundtrip_latency_by_job_id"),
            "ajamato client latency by job ID",
            RpcMeasureConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY,
            Aggregation.Distribution.create(bucketBoundaries),
            Arrays.asList(RpcMeasureConstants.RPC_METHOD, JOB_ID_KEY));

    View finishedCount =
        View.create(
            Name.create("daveraff2_request_count_by_job_id"),
            "daveraff client request count by job ID",
            RpcMeasureConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY,
            Aggregation.Count.create(),
            Arrays.asList(RpcMeasureConstants.RPC_METHOD, JOB_ID_KEY));

    ViewManager viewManager = Stats.getViewManager();
    viewManager.registerView(latencyByJobId);
    viewManager.registerView(finishedCount);

    logger.info("Hello from Dave 4...");
    logger.info("Cloud Project ID: " + cloudProjectId);

    if (cloudProjectId != null) {
      /*
      MonitoredResource myResource = MonitoredResource.newBuilder()
          .setType("dataflow_job")
          .putLabels("project_id", "ajamato-gaming")
          .putLabels("job_id", "my_job")
          .putLabels("region", "us-east1")
          .build();*/
      logger.info("Using cloudProjectId:" + cloudProjectId);
      /*
      StackdriverTraceExporter.createAndRegister(
          StackdriverTraceConfiguration.builder().setProjectId(cloudProjectId).build());*/
      StackdriverStatsExporter.createAndRegister(
          StackdriverStatsConfiguration.builder()
              // .setMonitoredResource(myResource)
              .setProjectId(cloudProjectId)
              .setExportInterval(Duration.create(10, 0))
              .build());
    }

    // Register Prometheus exporters and export metrics to a Prometheus HTTPServer.
    PrometheusStatsCollector.createAndRegister();

    HelloWorldClient client = new HelloWorldClient(host, serverPort);

    // try {
    //   try (Scope ss = tagger.withTagContext(tctx)) {
    //     logger.info(
    //         "Current tag context equals tctx: " + tagger.getCurrentTagContext().equals(tctx));
    //     for (int i = 0; i < 100; i++) {
    //       client.greet(user);
    //     }
    //   }
    // } finally {
    //   client.shutdown();
    // }

    logger.info("Client sleeping, ^C to exit. Meanwhile you can view stats and spans on zpages.");
    try {
      while (true) {
        try (Scope ss = tagger.withTagContext(tctx)) {
          logger.info(
              "Current tag context equals tctx: " + tagger.getCurrentTagContext().equals(tctx));
          for (int i = 0; i < 10; i++) {
            client.greet(user);
          }
        }
        Thread.sleep(1000);
      }
    } catch (InterruptedException e) {
      logger.info("Exiting HelloWorldClient...");
    } finally {
      logger.info("Shutting down client");
      client.shutdown();
    }
  }
}
