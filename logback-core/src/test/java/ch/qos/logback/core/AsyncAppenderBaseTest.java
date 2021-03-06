/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.core;

import ch.qos.logback.core.helpers.NOPAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.testUtil.DelayingListAppender;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.StatusChecker;
import ch.qos.logback.core.testUtil.NPEAppender;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author Ceki G&uuml;lc&uuml;
 * @author Torsten Juergeleit
 */
public class AsyncAppenderBaseTest {

	Context context = new ContextBase();
	AsyncAppenderBase<Integer> asyncAppenderBase = new AsyncAppenderBase<Integer>();
	LossyAsyncAppender lossyAsyncAppender = new LossyAsyncAppender();
	DelayingListAppender<Integer> delayingListAppender = new DelayingListAppender<Integer>();
	ListAppender<Integer> listAppender = new ListAppender<Integer>();
	OnConsoleStatusListener onConsoleStatusListener = new OnConsoleStatusListener();
	StatusChecker statusChecker = new StatusChecker(context);

	@Before
	public void setUp() {
		onConsoleStatusListener.setContext(context);
		context.getStatusManager().add(onConsoleStatusListener);
		onConsoleStatusListener.start();

		asyncAppenderBase.setContext(context);
		lossyAsyncAppender.setContext(context);

		listAppender.setContext(context);
		listAppender.setName("list");
		listAppender.start();

		delayingListAppender.setContext(context);
		delayingListAppender.setName("list");
		delayingListAppender.start();
	}

	@Test(timeout = 2000)
	public void smoke() {
		asyncAppenderBase.addAppender(listAppender);
		asyncAppenderBase.start();
		asyncAppenderBase.doAppend(0);
		asyncAppenderBase.stop();
		verify(listAppender, 1);
	}

	@Test
	public void exceptionsShouldNotCauseHalting() throws InterruptedException {
		NPEAppender<Integer> npeAppender = new NPEAppender<Integer>();
		npeAppender.setName("bad");
		npeAppender.setContext(context);
		npeAppender.start();

		asyncAppenderBase.addAppender(npeAppender);
		asyncAppenderBase.start();
		assertTrue(asyncAppenderBase.isStarted());
		for (int i = 0; i < 10; i++)
			asyncAppenderBase.append(i);

		asyncAppenderBase.stop();
		assertFalse(asyncAppenderBase.isStarted());
		assertEquals(AppenderBase.ALLOWED_REPEATS,
				statusChecker
						.matchCount("Appender \\[bad\\] failed to append."));
	}

	@Test(timeout = 2000)
	public void emptyQueueShouldBeStoppable() {
		asyncAppenderBase.addAppender(listAppender);
		asyncAppenderBase.start();
		asyncAppenderBase.stop();
		verify(listAppender, 0);
	}

	@Test(timeout = 2000)
	public void workerShouldStopEvenIfInterruptExceptionConsumedWithinSubappender() {
		delayingListAppender.delay = 100;
		asyncAppenderBase.addAppender(delayingListAppender);
		asyncAppenderBase.start();
		asyncAppenderBase.doAppend(0);
		asyncAppenderBase.stop();
		verify(delayingListAppender, 1);
		assertTrue(delayingListAppender.interrupted);
	}

	@Test(timeout = 2000)
	public void noEventLoss() {
		int bufferSize = 10;
		int loopLen = bufferSize * 2;
		asyncAppenderBase.addAppender(delayingListAppender);
		asyncAppenderBase.setQueueSize(bufferSize);
		asyncAppenderBase.start();
		for (int i = 0; i < loopLen; i++) {
			asyncAppenderBase.doAppend(i);
		}
		asyncAppenderBase.stop();
		verify(delayingListAppender, loopLen);
	}

	@Test(timeout = 2000)
	public void lossyAppenderShouldOnlyLooseCertainEvents() {
		int bufferSize = 5;
		int loopLen = bufferSize * 2;
		lossyAsyncAppender.addAppender(delayingListAppender);
		lossyAsyncAppender.setQueueSize(bufferSize);
		lossyAsyncAppender.setDiscardingThreshold(1);
		lossyAsyncAppender.start();
		for (int i = 0; i < loopLen; i++) {
			lossyAsyncAppender.doAppend(i);
		}
		lossyAsyncAppender.stop();
		verify(delayingListAppender, loopLen - 2);
	}

	@Test(timeout = 2000)
	public void lossyAppenderShouldBeNonLossyIfDiscardingThresholdIsZero() {
		int bufferSize = 5;
		int loopLen = bufferSize * 2;
		lossyAsyncAppender.addAppender(delayingListAppender);
		lossyAsyncAppender.setQueueSize(bufferSize);
		lossyAsyncAppender.setDiscardingThreshold(0);
		lossyAsyncAppender.start();
		for (int i = 0; i < loopLen; i++) {
			lossyAsyncAppender.doAppend(i);
		}
		lossyAsyncAppender.stop();
		verify(delayingListAppender, loopLen);
	}

	@Test
	public void invalidQueueCapacityShouldResultInNonStartedAppender() {
		asyncAppenderBase.addAppender(new NOPAppender<Integer>());
		asyncAppenderBase.setQueueSize(0);
		assertEquals(0, asyncAppenderBase.getQueueSize());
		asyncAppenderBase.start();
		assertFalse(asyncAppenderBase.isStarted());
		statusChecker.assertContainsMatch("Invalid queue size");
	}

	@SuppressWarnings("deprecation")
	@Test
	public void workerThreadFlushesOnStop() {
		int loopLen = 5;
		int maxRuntime = (loopLen + 1)
				* Math.max(1000, delayingListAppender.delay);
		ListAppender<Integer> la = delayingListAppender;
		asyncAppenderBase.addAppender(la);
		asyncAppenderBase.setDiscardingThreshold(0);
		asyncAppenderBase.setMaxFlushTime(maxRuntime);
		asyncAppenderBase.start();
		asyncAppenderBase.worker.suspend();

		for (int i = 0; i < loopLen; i++) {
			asyncAppenderBase.doAppend(i);
		}
		assertEquals(loopLen, asyncAppenderBase.getNumberOfElementsInQueue());
		assertEquals(0, la.list.size());

		asyncAppenderBase.worker.resume();
		asyncAppenderBase.stop();

		assertEquals(0, asyncAppenderBase.getNumberOfElementsInQueue());
		verify(la, loopLen);
	}

	@SuppressWarnings("deprecation")
	@Test
	public void stopExitsWhenMaxRuntimeReached() throws InterruptedException {
		int maxRuntime = 1; // runtime of 0 means wait forever, so use 1 ms
							// instead
		int loopLen = 10;
		ListAppender<Integer> la = delayingListAppender;
		asyncAppenderBase.addAppender(la);
		asyncAppenderBase.setMaxFlushTime(maxRuntime);
		asyncAppenderBase.start();

		for (int i = 0; i < loopLen; i++) {
			asyncAppenderBase.doAppend(i);
		}

		asyncAppenderBase.stop();

		// suspend the thread so that we can make the following assertions
		// without race conditions
		asyncAppenderBase.worker.suspend();

		// confirms that stop exited when runtime reached
		statusChecker.assertContainsMatch("Max queue flush timeout \\("
				+ maxRuntime + " ms\\) exceeded.");

		// confirms that the number of events posted are the number of events
		// removed from the queue
		assertEquals(la.list.size(),
				loopLen - asyncAppenderBase.getNumberOfElementsInQueue());

		// resume the thread to let it finish processing
		asyncAppenderBase.worker.resume();

		asyncAppenderBase.worker.join();

		// confirms that all entries do end up being flushed if we wait long
		// enough
		verify(la, loopLen);
	}


	// Interruption of current thread when in doAppend method should not be consumed
	// by async appender. See also http://jira.qos.ch/browse/LOGBACK-910
	@Test
	public void verifyInterruptionIsNotSwallowed() {
		asyncAppenderBase.addAppender(delayingListAppender);
		asyncAppenderBase.start();
		Thread.currentThread().interrupt();
		asyncAppenderBase.doAppend(new Integer(0));
		assertTrue(Thread.currentThread().isInterrupted());
		// clear flag for next test
		Thread.interrupted();
	}
	
	@Test
	public void verifyInterruptionOfWorkerIsSwallowed() {
      asyncAppenderBase.addAppender(delayingListAppender);
	  asyncAppenderBase.start();
	  asyncAppenderBase.stop();
	  assertFalse(asyncAppenderBase.worker.isInterrupted());
	}
	
	
	private void verify(ListAppender<Integer> la, int expectedSize) {
		assertFalse(la.isStarted());
		assertEquals(expectedSize, la.list.size());
		statusChecker.assertIsErrorFree();
		statusChecker
				.assertContainsMatch("Worker thread will flush remaining events before exiting.");
	}

	static class LossyAsyncAppender extends AsyncAppenderBase<Integer> {
		@Override
		protected boolean isDiscardable(Integer i) {
			return (i % 3 == 0);
		}
	}
}
