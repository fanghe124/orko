package com.grahamcrockford.orko.guardian;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.google.common.eventbus.EventBus;
import com.google.inject.Injector;
import com.grahamcrockford.orko.OrkoConfiguration;
import com.grahamcrockford.orko.exchange.AccountServiceFactory;
import com.grahamcrockford.orko.exchange.ExchangeService;
import com.grahamcrockford.orko.exchange.TradeServiceFactory;
import com.grahamcrockford.orko.marketdata.MarketDataSubscriptionManager;
import com.grahamcrockford.orko.notification.Status;
import com.grahamcrockford.orko.notification.StatusUpdateService;
import com.grahamcrockford.orko.spi.Job;
import com.grahamcrockford.orko.spi.JobControl;
import com.grahamcrockford.orko.spi.JobProcessor;
import com.grahamcrockford.orko.submit.JobAccess;
import com.grahamcrockford.orko.submit.JobLocker;

public class TestJobExecutionIntegration {

  private static final int WAIT_SECONDS = 3;

  private static final String JOB1 = "JOB1";
  private static final String JOB2 = "JOB2";
  private static final String JOB3 = "JOB3";

  @Mock private JobAccess jobAccess;
  @Mock private JobLocker jobLocker;
  @Mock private Injector injector;
  @Mock private ExchangeService exchangeService;
  @Mock private TradeServiceFactory tradeServiceFactory;
  @Mock private AccountServiceFactory accountServiceFactory;
  @Mock private StatusUpdateService statusUpdateService;

  private EventBus asyncEventBus;
  private JobRunner jobSubmitter;
  private GuardianLoop guardianLoop1;
  private GuardianLoop guardianLoop2;
  private MarketDataSubscriptionManager marketDataSubscriptionManager;

  private ExecutorService executor;

  private final Set<Job> activeJobs = Collections.newSetFromMap(new ConcurrentHashMap<>());

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);

    asyncEventBus = new EventBus();

    when(injector.getInstance(TestingJobProcessor.Factory.class)).thenReturn(new TestingJobProcessor.Factory() {
      @Override
      public JobProcessor<TestingJob> create(TestingJob job, JobControl jobControl) {
        return new TestingJobProcessor(job, jobControl, asyncEventBus);
      }
    });

    when(jobAccess.list()).thenReturn(activeJobs);

    OrkoConfiguration config = new OrkoConfiguration();
    config.setLoopSeconds(1);

    executor = Executors.newCachedThreadPool();
    jobSubmitter = new JobRunner(jobAccess, jobLocker, injector, asyncEventBus, statusUpdateService);
    guardianLoop1 = new GuardianLoop(jobAccess, jobSubmitter, asyncEventBus, config);
    guardianLoop2 = new GuardianLoop(jobAccess, jobSubmitter, asyncEventBus, config);
    marketDataSubscriptionManager = new MarketDataSubscriptionManager(exchangeService, config, tradeServiceFactory, accountServiceFactory, new EventBus());
  }


  /**
   * Just does nothing and makes sure we can start and stop cleanly.
   */
  @Test
  public void testNothingRunningCleanShutdown() throws Exception {
    start();
  }


  /**
   * Enqueues three synchronous jobs and makes sure they all complete correctly
   */
  @Test
  public void testSyncRun() throws Exception {
    CountDownLatch completion1 = new CountDownLatch(1);
    CountDownLatch completion2 = new CountDownLatch(1);
    CountDownLatch completion3 = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).completionLatch(completion1).build(), false);
    addJob(TestingJob.builder().id(JOB2).completionLatch(completion2).build(), false);
    addJob(TestingJob.builder().id(JOB3).completionLatch(completion3).build(), false);

    start();

    Assert.assertTrue(completion1.await(WAIT_SECONDS, TimeUnit.SECONDS));
    Assert.assertTrue(completion2.await(WAIT_SECONDS, TimeUnit.SECONDS));
    Assert.assertTrue(completion3.await(WAIT_SECONDS, TimeUnit.SECONDS));
  }


  /**
   * Ensures that an exception thrown during startup is treated as transient
   * @throws InterruptedException
   */
  @Test
  public void testFailOnStart() throws InterruptedException {
    CountDownLatch completion = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).completionLatch(completion).failOnStart(true).build(), false);
    start();
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));
    verify(statusUpdateService).status(JOB1, Status.FAILURE_TRANSIENT);
    verifyNoMoreInteractions(statusUpdateService);
  }


  /**
   * Ensures that an exception thrown during stop is handled
   * @throws InterruptedException
   */
  @Test
  public void testFailOnStopNonResident() throws InterruptedException {
    CountDownLatch completion = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).completionLatch(completion).failOnStop(true).build(), false);
    start();
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));
    verify(statusUpdateService).status(JOB1, Status.SUCCESS);
    verifyNoMoreInteractions(statusUpdateService);
  }


  /**
   * Ensures that an exception thrown during stop is handled
   * @throws InterruptedException
   */
  @Test
  public void testFailOnStopResident() throws InterruptedException {
    CountDownLatch completion = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).completionLatch(completion).runAsync(true).stayResident(false).failOnStop(true).build(), false);
    start();
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));


    InOrder inOrder = inOrder(statusUpdateService);
    inOrder.verify(statusUpdateService).status(JOB1, Status.RUNNING);
    inOrder.verify(statusUpdateService).status(JOB1, Status.SUCCESS);
    verifyNoMoreInteractions(statusUpdateService);
  }


  /**
   * Ensures that we correctly handle a mid-run abort
   * @throws InterruptedException
   */
  @Test
  public void testFailOnTick() throws InterruptedException {
    CountDownLatch completion = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).completionLatch(completion).runAsync(true).stayResident(true).failOnTick(true).build(), false);
    start();
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));

    InOrder inOrder = inOrder(statusUpdateService);
    inOrder.verify(statusUpdateService).status(JOB1, Status.RUNNING);
    inOrder.verify(statusUpdateService).status(JOB1, Status.FAILURE_PERMANENT);
    verifyNoMoreInteractions(statusUpdateService);
  }


  /**
   * This job should start and then stay resident until forcefully killed.
   */
  @Test
  public void testASyncResidentRunKillByLostLock() throws Exception {
    CountDownLatch completion = new CountDownLatch(1);
    AtomicBoolean hasLock = addJob(TestingJob.builder().id(JOB1).runAsync(true).stayResident(true).completionLatch(completion).build(), false);

    start();

    // Should still be running after 10 seconds
    Assert.assertFalse(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));

    // Kill its lock
    hasLock.set(false);

    // Should die
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));
  }


  /**
   * This job should start and then stay resident until we kill them by shutting down the system.
   */
  @Test
  public void testASyncResidentRunKillByShutdown() throws Exception {
    CountDownLatch completion = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).runAsync(true).stayResident(true).completionLatch(completion).build(), false);

    start();

    // Should still be running after 10 seconds
    Assert.assertFalse(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));

    // Mimic shutdown
    asyncEventBus.post(StopEvent.INSTANCE);

    // Should die
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));
  }


  /**
   * Handle jobs getting updated and continuing.
   */
  @Test
  public void testUpdate() throws Exception {
    CountDownLatch start = new CountDownLatch(2);
    CountDownLatch completion = new CountDownLatch(2);
    addJob(TestingJob.builder().id(JOB1).runAsync(true).stayResident(true).update(true).startLatch(start).completionLatch(completion).build(), false);

    start();

    // We expect to have started twice but finished once
    Assert.assertTrue(start.await(WAIT_SECONDS, TimeUnit.SECONDS));
    Assert.assertFalse(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));

    // Mimic shutdown
    asyncEventBus.post(StopEvent.INSTANCE);

    // Should die
    Assert.assertTrue(completion.await(WAIT_SECONDS, TimeUnit.SECONDS));
  }


  /**
   * Three jobs which should run a single tick then stop.
   */
  @Test
  public void testASyncNonResidentRun() throws Exception {
    CountDownLatch completion1 = new CountDownLatch(1);
    CountDownLatch completion2 = new CountDownLatch(1);
    CountDownLatch completion3 = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).runAsync(true).stayResident(false).completionLatch(completion1).build(), false);
    addJob(TestingJob.builder().id(JOB2).runAsync(true).stayResident(false).completionLatch(completion2).build(), false);
    addJob(TestingJob.builder().id(JOB3).runAsync(true).stayResident(false).completionLatch(completion3).build(), false);

    start();

    Assert.assertTrue(completion1.await(WAIT_SECONDS, TimeUnit.SECONDS));
    Assert.assertTrue(completion2.await(WAIT_SECONDS, TimeUnit.SECONDS));
    Assert.assertTrue(completion3.await(WAIT_SECONDS, TimeUnit.SECONDS));
  }


  /**
   * Makes sure a job doesn't start if the keepalive thinks it's already running.
   */
  @Test
  public void testDontRunIfHandledElsewhere() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    addJob(TestingJob.builder().id(JOB1).startLatch(startLatch).build(), true);

    start();

    Assert.assertFalse(startLatch.await(WAIT_SECONDS, TimeUnit.SECONDS));
  }


  private AtomicBoolean addJob(Job job, boolean alreadyLocked) {
    AtomicBoolean locked = new AtomicBoolean(alreadyLocked);
    AtomicBoolean hasLock = new AtomicBoolean(true);
    when(jobLocker.attemptLock(Mockito.eq(job.id()), Mockito.any(UUID.class))).thenAnswer(a -> !locked.getAndSet(true));
    when(jobLocker.updateLock(Mockito.eq(job.id()), Mockito.any(UUID.class))).thenAnswer(a -> hasLock.get());
    when(jobAccess.load(job.id())).thenReturn(job);
    activeJobs.add(job);
    return hasLock;
  }

  private void start() {
    guardianLoop1.startAsync();
    guardianLoop2.startAsync();
    marketDataSubscriptionManager.startAsync();
    guardianLoop1.awaitRunning();
    guardianLoop2.awaitRunning();
    marketDataSubscriptionManager.awaitRunning();
  }

  @After
  public void tearDown() throws Exception {
    marketDataSubscriptionManager.stopAsync();
    guardianLoop1.stopAsync();
    guardianLoop2.stopAsync();
    marketDataSubscriptionManager.awaitTerminated();
    guardianLoop1.awaitTerminated();
    guardianLoop2.awaitTerminated();
    executor.shutdownNow();
    executor.awaitTermination(30, TimeUnit.SECONDS);
  }
}