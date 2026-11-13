package in.quickslot.demo;

import in.quickslot.availability.AvailabilityService;
import in.quickslot.inventory.Room;
import in.quickslot.inventory.RoomRepository;
import in.quickslot.shared.ApiException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OsDemoService {
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private final JdbcTemplate jdbc;
  private final RoomRepository rooms;

  public OsDemoService(JdbcTemplate jdbc, RoomRepository rooms) {
    this.jdbc = jdbc;
    this.rooms = rooms;
  }

  // --- DTOs ---
  public record FcfsJobInput(String jobId, double arrivalTime, double burstTime) {}

  public record FcfsJobMetric(
      String jobId,
      double arrivalTime,
      double burstTime,
      double startTime,
      double completionTime,
      double turnaroundTime,
      double waitingTime) {}

  public record GanttBlock(String jobId, double start, double end, double duration, boolean isIdle) {}

  public record FcfsScheduleResponse(
      List<FcfsJobMetric> jobs,
      double averageTurnaroundTime,
      double averageWaitingTime,
      double throughput,
      List<GanttBlock> ganttChart) {}

  public record DeadlockDemoResponse(
      String mode,
      boolean deadlockOccurred,
      Map<String, String> coffmanConditions,
      List<String> executionTimeline,
      String explanation) {}

  public record SyncRaceDemoResponse(
      boolean useMutex,
      int numThreads,
      int incrementsPerThread,
      long expectedCounter,
      long actualCounter,
      long lostUpdates,
      boolean raceDetected,
      String criticalSectionExplanation) {}

  public record ConcurrencyTestResponse(
      int totalConcurrentRequests,
      int confirmedBookings,
      int rejectedConflicts,
      int exclusionConstraintViolations,
      double executionTimeSeconds,
      boolean isSafe,
      String verificationMessage) {}

  // 1. FCFS Process Scheduling Engine
  public FcfsScheduleResponse runFcfs(List<FcfsJobInput> inputJobs) {
    List<FcfsJobInput> jobs = (inputJobs != null && !inputJobs.isEmpty())
        ? inputJobs
        : List.of(
            new FcfsJobInput("P1 (Timetable Parse)", 0.0, 4.0),
            new FcfsJobInput("P2 (Cache Refill)", 1.0, 2.0),
            new FcfsJobInput("P3 (Outbox Dispatch)", 2.0, 1.0),
            new FcfsJobInput("P4 (Audit Log Flush)", 4.0, 3.0),
            new FcfsJobInput("P5 (PDF Hash Verification)", 6.0, 2.0));

    var sorted = new ArrayList<>(jobs);
    sorted.sort(Comparator.comparingDouble(FcfsJobInput::arrivalTime));

    double currentTime = 0.0;
    List<GanttBlock> gantt = new ArrayList<>();
    List<FcfsJobMetric> metrics = new ArrayList<>();
    double totalTat = 0.0;
    double totalWt = 0.0;

    for (var job : sorted) {
      if (currentTime < job.arrivalTime()) {
        gantt.add(new GanttBlock("IDLE", round(currentTime), round(job.arrivalTime()), round(job.arrivalTime() - currentTime), true));
        currentTime = job.arrivalTime();
      }

      double start = currentTime;
      double completion = start + job.burstTime();
      double tat = completion - job.arrivalTime();
      double wt = tat - job.burstTime();

      gantt.add(new GanttBlock(job.jobId(), round(start), round(completion), round(job.burstTime()), false));
      metrics.add(new FcfsJobMetric(job.jobId(), round(job.arrivalTime()), round(job.burstTime()), round(start), round(completion), round(tat), round(wt)));

      totalTat += tat;
      totalWt += wt;
      currentTime = completion;
    }

    int n = sorted.size();
    double avgTat = n > 0 ? round(totalTat / n) : 0.0;
    double avgWt = n > 0 ? round(totalWt / n) : 0.0;
    double totalDuration = currentTime > 0 ? currentTime : 1.0;
    double throughput = round((double) n / totalDuration);

    return new FcfsScheduleResponse(metrics, avgTat, avgWt, throughput, gantt);
  }

  // 2. Deadlock & Resource Hierarchy (Coffman Conditions)
  public DeadlockDemoResponse runDeadlock(String mode) {
    boolean prevent = "prevent".equalsIgnoreCase(mode);
    ReentrantLock lockA = new ReentrantLock();
    ReentrantLock lockB = new ReentrantLock();
    List<String> timeline = Collections.synchronizedList(new ArrayList<>());
    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    Runnable log = () -> {};
    var logMsg = new java.util.function.Consumer<String>() {
      public void accept(String msg) {
        timeline.add("[" + LocalTime.now().format(dtf) + "] " + msg);
      }
    };

    boolean deadlockDetected;
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      if (!prevent) {
        // Mode 'deadlock': Circular Wait
        // Thread 1: Lock A -> sleep -> Lock B
        // Thread 2: Lock B -> sleep -> Lock A
        Future<?> f1 = executor.submit(() -> {
          logMsg.accept("Thread 1: Attempting to acquire Resource A (Room A)...");
          lockA.lock();
          try {
            logMsg.accept("Thread 1: Acquired Resource A. Holding Resource A and sleeping 150ms...");
            Thread.sleep(150);
            logMsg.accept("Thread 1: Attempting to acquire Resource B (while holding A)...");
            boolean acquired = lockB.tryLock(600, TimeUnit.MILLISECONDS);
            if (!acquired) {
              logMsg.accept("Thread 1: TIMEOUT! Could not acquire Resource B. Deadlock / Circular wait detected!");
            } else {
              try {
                logMsg.accept("Thread 1: Acquired Resource B.");
              } finally {
                lockB.unlock();
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lockA.unlock();
            logMsg.accept("Thread 1: Released Resource A.");
          }
        });

        Future<?> f2 = executor.submit(() -> {
          logMsg.accept("Thread 2: Attempting to acquire Resource B (Projector B)...");
          lockB.lock();
          try {
            logMsg.accept("Thread 2: Acquired Resource B. Holding Resource B and sleeping 150ms...");
            Thread.sleep(150);
            logMsg.accept("Thread 2: Attempting to acquire Resource A (while holding B)...");
            boolean acquired = lockA.tryLock(600, TimeUnit.MILLISECONDS);
            if (!acquired) {
              logMsg.accept("Thread 2: TIMEOUT! Could not acquire Resource A. Deadlock / Circular wait detected!");
            } else {
              try {
                logMsg.accept("Thread 2: Acquired Resource A.");
              } finally {
                lockA.unlock();
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lockB.unlock();
            logMsg.accept("Thread 2: Released Resource B.");
          }
        });

        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
        deadlockDetected = true;
      } else {
        // Mode 'prevent': Strict Resource Ordering Hierarchy (Resource A < Resource B)
        // Both threads acquire Resource A before Resource B
        Future<?> f1 = executor.submit(() -> {
          logMsg.accept("Safe Thread 1: Acquiring Resource A (hierarchy order 1)...");
          lockA.lock();
          try {
            logMsg.accept("Safe Thread 1: Acquired A. Now acquiring Resource B (hierarchy order 2)...");
            lockB.lock();
            try {
              logMsg.accept("Safe Thread 1: Acquired both A and B. Processing critical section safely...");
              Thread.sleep(100);
            } finally {
              lockB.unlock();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lockA.unlock();
            logMsg.accept("Safe Thread 1: Released both resources successfully.");
          }
        });

        Future<?> f2 = executor.submit(() -> {
          logMsg.accept("Safe Thread 2: Acquiring Resource A (hierarchy order 1 - obeys ordering)...");
          lockA.lock();
          try {
            logMsg.accept("Safe Thread 2: Acquired A. Now acquiring Resource B (hierarchy order 2)...");
            lockB.lock();
            try {
              logMsg.accept("Safe Thread 2: Acquired both A and B. Processing critical section safely...");
              Thread.sleep(100);
            } finally {
              lockB.unlock();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lockA.unlock();
            logMsg.accept("Safe Thread 2: Released both resources successfully.");
          }
        });

        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
        deadlockDetected = false;
      }
    } catch (Exception e) {
      deadlockDetected = true;
    } finally {
      executor.shutdown();
    }

    String explanation = prevent
        ? "Deadlock was completely PREVENTED by enforcing a Global Resource Ordering Hierarchy (Resource A < Resource B). "
            + "Because both threads acquire Resource A before Resource B, Circular Wait is mathematically impossible! "
            + "This is the exact same design pattern used in database booking transactions: locking resource rows in ascending key order."
        : "Deadlock occurred because all 4 Coffman conditions were met: "
            + "1) Mutual Exclusion: Resources A and B cannot be shared. "
            + "2) Hold and Wait: Thread 1 holds A and waits for B, while Thread 2 holds B and waits for A. "
            + "3) No Preemption: Neither thread can forcibly seize a resource held by another. "
            + "4) Circular Wait: Thread 1 -> Resource B -> Thread 2 -> Resource A -> Thread 1. "
            + "The demonstration detected this condition safely using a controlled lock timeout.";

    Map<String, String> coffman = Map.of(
        "Mutual Exclusion", "Resources A and B are non-shareable exclusive locks.",
        "Hold and Wait", "Processes hold currently allocated resources while waiting for additional resources.",
        "No Preemption", "A resource cannot be forcibly preempted from a holding thread.",
        "Circular Wait", "A closed chain of threads exists such that each thread holds at least one resource needed by the next.");

    return new DeadlockDemoResponse(mode, deadlockDetected, coffman, timeline, explanation);
  }

  // 3. Critical Section & Mutex Synchronization
  private static final Object SYNC_LOCK = new Object();
  private static volatile int sharedCounter = 0;

  public SyncRaceDemoResponse runSync(int numThreads, int incrementsPerThread, boolean useMutex) {
    int threadsCount = Math.max(2, Math.min(numThreads, 30));
    int increments = Math.max(100, Math.min(incrementsPerThread, 20000));
    sharedCounter = 0;

    ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
    List<Callable<Void>> tasks = new ArrayList<>();

    for (int i = 0; i < threadsCount; i++) {
      tasks.add(() -> {
        for (int k = 0; k < increments; k++) {
          if (useMutex) {
            synchronized (SYNC_LOCK) {
              sharedCounter++;
            }
          } else {
            // Unsynchronized: simulate non-atomic read-modify-write race condition
            int temp = sharedCounter;
            Thread.yield();
            sharedCounter = temp + 1;
          }
        }
        return null;
      });
    }

    try {
      executor.invokeAll(tasks);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      executor.shutdown();
    }

    long expected = (long) threadsCount * increments;
    long actual = sharedCounter;
    long lost = Math.max(0, expected - actual);
    boolean raceDetected = (lost > 0);

    String explanation = "Expected " + expected + " increments (" + threadsCount + " threads × " + increments + " iterations). "
        + (useMutex
            ? "With Mutual Exclusion (synchronized block), every increment executed atomically inside the critical section. No updates were lost."
            : "Without synchronization, multiple threads interleaved the non-atomic operation (read counter -> modify -> store counter). Result: "
                + lost + " updates were lost due to race conditions!");

    return new SyncRaceDemoResponse(
        useMutex, threadsCount, increments, expected, actual, lost, raceDetected, explanation);
  }

  // 4. PostgreSQL btree_gist Exclusion Constraint Concurrency Race
  public ConcurrencyTestResponse runConcurrencyRace(int numCompeting) {
    int competing = Math.max(2, Math.min(numCompeting, 50));
    var publicRooms = rooms.publicRooms();
    if (publicRooms.isEmpty()) {
      throw new ApiException(400, "No rooms available for concurrency race test.");
    }
    Room targetRoom = publicRooms.get(0);

    // Target a test date 30 days in the future to isolate from normal university operations
    LocalDate testDate = LocalDate.now(INDIA).plusDays(30);
    LocalTime startTime = LocalTime.of(18, 0);
    LocalTime endTime = LocalTime.of(19, 0);

    OffsetDateTime startsAt = testDate.atTime(startTime).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime endsAt = testDate.atTime(endTime).atZone(INDIA).toOffsetDateTime();

    // Clean up any old test occupancies for this test slot
    jdbc.update(
        "DELETE FROM occupancies WHERE room_id=? AND starts_at=? AND ends_at=?",
        targetRoom.id(), startsAt, endsAt);

    AtomicInteger confirmedCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);
    AtomicInteger exclusionViolations = new AtomicInteger(0);

    long startMillis = System.currentTimeMillis();
    ExecutorService executor = Executors.newFixedThreadPool(competing);
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < competing; i++) {
      final int contenderIndex = i + 1;
      futures.add(executor.submit(() -> {
        try {
          startGate.await(); // All threads release simultaneously
          String occupancyId = UUID.randomUUID().toString();
          String sourceId = UUID.randomUUID().toString();

          // Attempt atomic insert directly against PostgreSQL btree_gist exclusion constraint
          jdbc.update(
              "INSERT INTO occupancies(id, room_id, starts_at, ends_at, kind, source_id, active) VALUES (?,?,?,?,?,?,true)",
              occupancyId,
              targetRoom.id(),
              startsAt,
              endsAt,
              "BOOKING",
              sourceId);
          confirmedCount.incrementAndGet();
        } catch (DataAccessException dae) {
          conflictCount.incrementAndGet();
          String msg = dae.getMessage() != null ? dae.getMessage().toLowerCase() : "";
          if (msg.contains("exclusion") || msg.contains("no_overlapping_occupancies") || msg.contains("23p01")) {
            exclusionViolations.incrementAndGet();
          }
        } catch (Exception ex) {
          conflictCount.incrementAndGet();
        }
      }));
    }

    startGate.countDown(); // Fire all threads at once

    for (var f : futures) {
      try {
        f.get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {}
    }
    executor.shutdown();

    double elapsed = round((System.currentTimeMillis() - startMillis) / 1000.0);
    int confirmed = confirmedCount.get();
    int rejected = conflictCount.get();
    boolean isSafe = (confirmed == 1);

    // Clean up the test booking
    jdbc.update(
        "DELETE FROM occupancies WHERE room_id=? AND starts_at=? AND ends_at=?",
        targetRoom.id(), startsAt, endsAt);

    String verificationMsg = isSafe
        ? "SUCCESS: Out of " + competing + " concurrent transaction threads simultaneously targeting the exact same room and slot, "
            + "EXACTLY " + confirmed + " reservation was confirmed and " + rejected + " were rejected! "
            + "PostgreSQL 18 btree_gist physical exclusion constraint strictly prevented any double-booking."
        : "FAILURE: " + confirmed + " bookings confirmed! Concurrency control violated.";

    return new ConcurrencyTestResponse(
        competing, confirmed, rejected, exclusionViolations.get(), elapsed, isSafe, verificationMsg);
  }

  private double round(double val) {
    return Math.round(val * 100.0) / 100.0;
  }
}
