/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.performanceanalyzer.writer;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.performanceanalyzer.PerformanceAnalyzerApp;
import org.opensearch.performanceanalyzer.config.PerformanceAnalyzerController;
import org.opensearch.performanceanalyzer.http_action.config.PerformanceAnalyzerConfigAction;
import org.opensearch.performanceanalyzer.metrics.MetricsConfiguration;
import org.opensearch.performanceanalyzer.metrics.PerformanceAnalyzerMetrics;
import org.opensearch.performanceanalyzer.rca.framework.metrics.WriterMetrics;
import org.opensearch.performanceanalyzer.reader_writer_shared.Event;
import org.opensearch.performanceanalyzer.reader_writer_shared.EventLogFileHandler;

public class EventLogQueueProcessor {
    private static final Logger LOG = LogManager.getLogger(EventLogQueueProcessor.class);

    private final ScheduledExecutorService writerExecutor = Executors.newScheduledThreadPool(1);
    private final EventLogFileHandler eventLogFileHandler;
    private final long initialDelayMillis;
    private final long purgePeriodicityMillis;
    private final PerformanceAnalyzerController controller;
    private long lastTimeBucket;

    public EventLogQueueProcessor(
            EventLogFileHandler eventLogFileHandler,
            long initialDelayMillis,
            long purgePeriodicityMillis,
            PerformanceAnalyzerController controller) {
        this.eventLogFileHandler = eventLogFileHandler;
        this.initialDelayMillis = initialDelayMillis;
        this.purgePeriodicityMillis = purgePeriodicityMillis;
        this.lastTimeBucket = 0;
        this.controller = controller;
    }

    public void scheduleExecutor() {
        ScheduledFuture<?> futureHandle =
                writerExecutor.scheduleAtFixedRate(
                        this::purgeQueueAndPersist,
                        // The initial delay is critical here. The collector threads
                        // start immediately with the Plugin. This thread purges the
                        // queue and writes data to file. So, it waits for one run of
                        // the collectors to complete before it starts, so that the
                        // queue has elements to drain.
                        initialDelayMillis,
                        purgePeriodicityMillis,
                        TimeUnit.MILLISECONDS);
        new Thread(
                        () -> {
                            try {
                                futureHandle.get();
                            } catch (InterruptedException e) {
                                LOG.error("Scheduled execution was interrupted", e);
                            } catch (CancellationException e) {
                                LOG.warn("Watcher thread has been cancelled", e);
                            } catch (ExecutionException e) {
                                LOG.error("QueuePurger interrupted. Caused by ", e.getCause());
                            }
                        })
                .start();
    }

    // This executes every purgePeriodicityMillis interval.
    public void purgeQueueAndPersist() {
        // Return if the writer is not enabled.
        if (PerformanceAnalyzerConfigAction.getInstance() == null) {
            return;
        } else if (!controller.isPerformanceAnalyzerEnabled()) {
            // If PA is disabled, then we return as we don't want to generate
            // new files. But we also want to drain the queue so that when it is
            // enabled next, we don't have the current elements as they would be
            // old.
            if (PerformanceAnalyzerMetrics.metricQueue.size() > 0) {
                List<Event> metrics = new ArrayList<>();
                PerformanceAnalyzerMetrics.metricQueue.drainTo(metrics);
                LOG.info(
                        "Performance Analyzer no longer enabled. Drained the"
                                + "queue to remove stale data.");
            }
            return;
        }

        LOG.debug("Starting to purge the queue.");
        List<Event> metrics = new ArrayList<>();
        PerformanceAnalyzerMetrics.metricQueue.drainTo(metrics);
        LOG.debug("Queue draining successful.");

        long currentTimeMillis = System.currentTimeMillis();

        // Calculate the timestamp on the file. For example, lets say the
        // purging started at time 12.5 then all the events between 5-10
        // are written to a file with name 5.
        long timeBucket =
                PerformanceAnalyzerMetrics.getTimeInterval(
                                currentTimeMillis, MetricsConfiguration.SAMPLING_INTERVAL)
                        - MetricsConfiguration.SAMPLING_INTERVAL;

        // When we are trying to collect the metrics for the 5th-10th second,
        // but doing that in the 12.5th second, there is a chance that a
        // collector ran in the 11th second and pushed the metrics in the
        // queue. This thread, should be able to filter them and write them
        // to their appropriate file, which should be 10 and not 5.
        long nextTimeBucket = timeBucket + MetricsConfiguration.SAMPLING_INTERVAL;

        List<Event> currMetrics = new ArrayList<>();
        List<Event> nextMetrics = new ArrayList<>();

        for (Event entry : metrics) {
            if (entry.epoch == timeBucket) {
                currMetrics.add(entry);
            } else if (entry.epoch == nextTimeBucket) {
                nextMetrics.add(entry);
            } else {
                // increment stale_metrics count when metrics to be collected is falling behind the
                // current bucket
                PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                        WriterMetrics.STALE_METRICS, "", 1);
            }
        }

        LOG.debug("Start serializing and writing to file.");
        writeAndRotate(currMetrics, timeBucket, currentTimeMillis);
        if (!nextMetrics.isEmpty()) {
            // The next bucket metrics don't need to be considered for
            // rotation just yet. So, we just write them to the
            // <nextTimeBucket>.tmp
            eventLogFileHandler.writeTmpFile(nextMetrics, nextTimeBucket);
        }
        LOG.debug("Writing to disk complete.");
    }

    private void writeAndRotate(
            final List<Event> currMetrics, long currTimeBucket, long currentTime) {
        // Going by the continuing example, we will rotate the 5.tmp file to
        // 5, which contains the metrics with epoch 5-10, whenever the purger
        // runs after the 15th second.
        if (lastTimeBucket != 0 && lastTimeBucket != currTimeBucket) {
            eventLogFileHandler.renameFromTmp(lastTimeBucket);
        }
        // This appends the data to a file named <currTimeBucket>.tmp
        eventLogFileHandler.writeTmpFile(currMetrics, currTimeBucket);
        lastTimeBucket = currTimeBucket;
    }
}
