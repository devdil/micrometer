/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.statsd;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.histogram.StatsConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.tcp.TcpResources;
import reactor.util.concurrent.Queues;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * @author Jon Schneider
 */
public class StatsdMeterRegistry extends MeterRegistry {
    private final StatsdConfig statsdConfig;

    private volatile UnicastProcessor<String> publisher;

    private Disposable.Swap udpClient = Disposables.swap();

    private final Collection<StatsdPollable> pollableMeters = Collections.synchronizedCollection(new LinkedList<>());

    // VisibleForTesting
    Disposable.Swap meterPoller = Disposables.swap();

    public StatsdMeterRegistry(StatsdConfig config, Clock clock) {
        super(clock);

        this.statsdConfig = config;

        switch (statsdConfig.flavor()) {
            case Datadog:
                config().namingConvention(NamingConvention.dot);
                break;
            default:
                config().namingConvention(NamingConvention.camelCase);
        }

        this.publisher = UnicastProcessor.create(Queues.<String>get(statsdConfig.queueSize()).get());
        gauge("statsd.queue.size", this.publisher, UnicastProcessor::size);
        gauge("statsd.queue.capacity", this.publisher, UnicastProcessor::getBufferSize);

        if (config.enabled())
            start();
    }

    public void start() {
        // TODO this will get simpler when this issue is addressed:
        // https://github.com/reactor/reactor-netty/issues/174
        Mono
            .<NettyContext>create(sink -> {
                ClientOptions options = new ClientOptions(ClientOptions.builder()
                    .loopResources(TcpResources.get())
                    .poolResources(TcpResources.get())) {
                    @Override
                    protected boolean useDatagramChannel() {
                        return true;
                    }
                };

                Bootstrap b = options.get();

                SocketAddress adr = new InetSocketAddress(statsdConfig.host(), statsdConfig.port());
                b.remoteAddress(adr);

                ContextHandler<?> h = ContextHandler.newClientContext(sink,
                    options,
                    new LoggingHandler(StatsdMeterRegistry.class),
                    false,
                    adr,
                    (ch, c, msg) -> ChannelOperations.bind(ch, (in, out) -> {
                        out.options(NettyPipeline.SendOptions::flushOnEach)
                            .sendString(publisher)
                            .then().subscribe();
                        return Flux.never();
                    }, c));

                b.handler(h);
                h.setFuture(b.connect());
            })
            .subscribe(client -> {
                this.udpClient.replace(client);

                // now that we're connected, start polling gauges
                meterPoller.replace(Flux.interval(statsdConfig.pollingFrequency())
                    .doOnEach(n -> {
                        synchronized (pollableMeters) {
                            pollableMeters.forEach(StatsdPollable::poll);
                        }
                    })
                    .subscribe());
            });
    }

    public void stop() {
        udpClient.dispose();
        meterPoller.dispose();
    }

    @Override
    protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> f) {
        StatsdGauge<T> gauge = new StatsdGauge<>(id, lineBuilder(id), publisher, obj, f);
        pollableMeters.add(gauge);
        return gauge;
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        return new StatsdCounter(id, lineBuilder(id), publisher);
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id) {
        StatsdLongTaskTimer ltt = new StatsdLongTaskTimer(id, lineBuilder(id), publisher, clock);
        pollableMeters.add(ltt);
        return ltt;
    }

    private final DecimalFormat percentileFormat = new DecimalFormat("#.####");

    @Override
    protected Timer newTimer(Meter.Id id, StatsConfig statsConfig) {
        Timer timer = new StatsdTimer(id, lineBuilder(id), publisher, clock, statsConfig, statsdConfig.step().toMillis());

        for (double percentile : statsConfig.getPercentiles()) {
            switch(statsdConfig.flavor()) {
                case Datadog:
                    gauge(id.getName() + "." + percentileFormat.format(percentile) + "percentile", timer,
                        t -> t.percentile(percentile, getBaseTimeUnit()));
                    break;
                case Telegraf:
                    gauge(id.getName() + "_percentile_" + percentileFormat.format(percentile), timer,
                        t -> t.percentile(percentile, getBaseTimeUnit()));
                    break;
                case Etsy:
                    gauge(id.getName(), Tags.concat(getConventionTags(id), "percentile", percentileFormat.format(percentile)),
                        timer, t -> t.percentile(percentile, getBaseTimeUnit()));
                    break;
            }
        }

        if(statsConfig.isPublishingHistogram()) {
            for (Long bucket : statsConfig.getHistogramBuckets(false)) {
                more().counter(id.getName(), Tags.concat(getConventionTags(id), "bucket", Long.toString(bucket), null),
                    timer, t -> t.histogramCountAtValue(bucket));
            }
        }

        return timer;
    }

    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id, StatsConfig statsConfig) {
        DistributionSummary summary = new StatsdDistributionSummary(id, lineBuilder(id), publisher, clock, statsConfig, statsdConfig.step().toMillis());

        for (double percentile : statsConfig.getPercentiles()) {
            switch(statsdConfig.flavor()) {
                case Datadog:
                    gauge(id.getName() + "." + percentileFormat.format(percentile) + "percentile", summary,
                        s -> s.percentile(percentile));
                    break;
                case Telegraf:
                    gauge(id.getName() + "_percentile_" + percentileFormat.format(percentile), summary,
                        s -> s.percentile(percentile));
                    break;
                case Etsy:
                    gauge(id.getName(), Tags.concat(getConventionTags(id), "percentile", percentileFormat.format(percentile)),
                        summary, s -> s.percentile(percentile));
                    break;
            }
        }

        if(statsConfig.isPublishingHistogram()) {
            for (Long bucket : statsConfig.getHistogramBuckets(false)) {
                more().counter(id.getName(), Tags.concat(id.getTags(), "bucket", Long.toString(bucket), null),
                    summary, s -> s.histogramCountAtValue(bucket));
            }
        }

        return summary;
    }

    @Override
    protected void newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        measurements.forEach(ms -> {
            StatsdLineBuilder line = lineBuilder(id);
            switch (ms.getStatistic()) {
                case Count:
                case Total:
                case TotalTime:
                    pollableMeters.add(() -> publisher.onNext(line.count((long) ms.getValue(), ms.getStatistic())));
                    break;
                case Value:
                case ActiveTasks:
                case Duration:
                case Unknown:
                    pollableMeters.add(() -> publisher.onNext(line.gauge(ms.getValue(), ms.getStatistic())));
                    break;
            }
        });
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    private StatsdLineBuilder lineBuilder(Meter.Id id) {
        return new StatsdLineBuilder(id, statsdConfig.flavor(), config().namingConvention());
    }
}