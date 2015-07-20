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

import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.kuppe.graphs.tarjan.GraphNode.Visited;

public class ConcurrentFastSCC {
	
	public Set<Set<GraphNode>> searchSCCs(final Graph graph) {
		// TODO Name threads inside executor to aid debugging.
		// see
		// http://www.nurkiewicz.com/2014/11/executorservice-10-tips-and-tricks.html
		final ForkJoinPool executor = new ForkJoinPool();

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

		// Wait until no SCCWorker is running and no SCCWorker is queued.
		executor.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		executor.shutdown();

		// Print runtime statistics
		if (graph.getName() != null) {
			System.out.println("Failed v locks: " + DecimalFormat.getInstance().format(SCCWorker.V_LOCK_FAIL.get()));
			System.out.println("Succss v locks: " + DecimalFormat.getInstance().format(SCCWorker.V_LOCK_SUCC.get()));
			System.out.println("Failed w locks: " + DecimalFormat.getInstance().format(SCCWorker.W_LOCK_FAIL.get()));
			System.out.println("Succss w locks: " + DecimalFormat.getInstance().format(SCCWorker.W_LOCK_SUCC.get()));
			System.out.println("Avg. succ length: " + DecimalFormat.getInstance().format(Graph.AVERAGE_FINDROOT_LENGTH.get() / Graph.AVERAGE_FINDROOT_CNT.get()));
			System.out.println("Avg. fail length: " + DecimalFormat.getInstance().format(Graph.AVERAGE_FAIL_LENGTH.get() / Graph.AVERAGE_FAIL_CNT.get()));
			System.out.println(graph.getName() + " : " + (System.currentTimeMillis() - start) / 1000 + " sec");
		}

		// Convert the result from a map with key being the parent in a tree of
		// the forest to just a set of SCCs. The parent is irrelevant from the
		// SCC POV and internal to the concurrent fast SCC algorithm.
		final Set<Set<GraphNode>> result = new HashSet<>(sccs.size());
		for (GraphNode graphNode : sccs.values()) {
			result.add(graphNode.getSCC());
		}
		return result;
	}
}