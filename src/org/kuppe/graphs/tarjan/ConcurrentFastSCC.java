/*******************************************************************************
 * Copyright (c) 2015 Microsoft Research. All rights reserved. 
 *
 * The MIT License (MIT)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy 
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software. 
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Contributors:
 *   Markus Alexander Kuppe - initial API and implementation
 ******************************************************************************/

package org.kuppe.graphs.tarjan;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.kuppe.graphs.tarjan.GraphNode.Visited;

import com.codahale.metrics.Counter;
import com.codahale.metrics.CsvReporter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Timer;

public class ConcurrentFastSCC {
	
	public static final MetricRegistry metrics = new MetricRegistry();
	private final Counter usedCores = ConcurrentFastSCC.metrics.counter(MetricRegistry.name("used-cores"));
	private final Timer timer = ConcurrentFastSCC.metrics.timer(MetricRegistry.name("timer"));
	private final Histogram histo = ConcurrentFastSCC.metrics.histogram(MetricRegistry.name("scc"));

	public Set<Set<GraphNode>> searchSCCs(final Graph graph) {
		final int availableProcessors = Runtime.getRuntime().availableProcessors();
		return searchSCCs(graph, Integer.getInteger(ConcurrentFastSCC.class.getName() + ".numCores", availableProcessors));
	}
	
	public Set<Set<GraphNode>> searchSCCs(final Graph graph, final int numCores) {
		
		metrics.reset();
		
		// TODO Name threads inside executor to aid debugging.
		// see
		// http://www.nurkiewicz.com/2014/11/executorservice-10-tips-and-tricks.html
		usedCores.inc(numCores); // record the number of used cores even though
									// it's static. It allows to determine the
									// core count in the final statistics.
		final ForkJoinPool executor = new ForkJoinPool(numCores);
		
		File directory = null;
		ScheduledReporter scheduledReporter = null;
		if (graph.getName() != null && !MetricRegistry.noop) {
			directory = new File(System.getProperty("java.io.tmpdir") + File.separator + graph.getName() + File.separator + System.currentTimeMillis());
			directory.mkdirs();
			scheduledReporter = CsvReporter.forRegistry(metrics).formatFor(Locale.US)
					.convertRatesTo(TimeUnit.SECONDS).convertDurationsTo(TimeUnit.MILLISECONDS)
					.build(directory);
			scheduledReporter.start(1, TimeUnit.SECONDS);
			startPoolMonitor(executor);
		}

		// The map of sccs passed around by SCCWorkers
		final Map<GraphNode, GraphNode> sccs = new ConcurrentHashMap<GraphNode, GraphNode>();
		
		// Take timestamp of when actual work started
		final long start = System.currentTimeMillis();
		
		// Submit a new worker for each graph node
		final Iterator<GraphNode> itr = graph.iterator();
		while (itr.hasNext()) {
			final GraphNode graphNode = itr.next();
			if (graphNode.isNot(Visited.POST) && graphNode.isRoot()) {
				executor.execute(new SCCWorker(executor, graph, sccs, graphNode));
			}
		}
		// measure time it takes to submit all jobs
		timer.update(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);

		// Wait until no SCCWorker is running and no SCCWorker is queued.
		executor.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		executor.shutdown();

		final long duration = System.currentTimeMillis() - start;
		timer.update(duration, TimeUnit.MILLISECONDS);

		if (scheduledReporter != null) {
			scheduledReporter.stop();
		}
		
		if (graph.getName() != null) {
			System.out.printf("Runtime (%s): %s sec (%s)\n", graph.getName(), duration / 1000L,
					directory != null ? directory.getAbsolutePath() : "no metrics collected");
		}

		// Convert the result from a map with key being the parent in a tree of
		// the forest to just a set of SCCs. The parent is irrelevant from the
		// SCC POV and internal to the concurrent fast SCC algorithm.
		final Set<Set<GraphNode>> result = new HashSet<>(sccs.size());
		for (GraphNode graphNode : sccs.values()) {
			final Set<GraphNode> scc = graphNode.getSCC();
			histo.update(scc.size());
			result.add(scc);
		}
		return result;
	}

	private void startPoolMonitor(final ForkJoinPool executor) {
		final Thread monitor = new Thread(new Runnable() {
			
			@Override
			public void run() {
				
				final Histogram poolSize = ConcurrentFastSCC.metrics.histogram(MetricRegistry.name("poolSize"));
				final Counter runningThreadCount = ConcurrentFastSCC.metrics.counter(MetricRegistry.name("runningThreadCount"));
				final Counter stealCount = ConcurrentFastSCC.metrics.counter(MetricRegistry.name("stealCount"));
				final Counter queuedSubmissionCount = ConcurrentFastSCC.metrics.counter(MetricRegistry.name("queuedSubmissionCount"));
				final Counter queuedTaskCount = ConcurrentFastSCC.metrics.counter(MetricRegistry.name("queuedTaskCount"));
				final Counter activeThreadCount = ConcurrentFastSCC.metrics.counter(MetricRegistry.name("activeThreadCount"));

				while (!executor.isTerminated()) {
					try {
						activeThreadCount.inc(executor.getActiveThreadCount());
						runningThreadCount.inc(executor.getRunningThreadCount());

						queuedSubmissionCount.inc(executor.getQueuedSubmissionCount());
						queuedTaskCount.inc(executor.getQueuedTaskCount());

						poolSize.update(executor.getPoolSize());
						stealCount.inc(executor.getStealCount());
						
						Thread.sleep(500L);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		});
		monitor.setDaemon(true); // make sure it doesn't stop the VM from shutting down
		monitor.start();
	}
}