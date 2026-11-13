package in.quickslot.demo;

import in.quickslot.demo.OsDemoService.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo")
public class OsDemoController {
  private final OsDemoService demoService;

  public OsDemoController(OsDemoService demoService) {
    this.demoService = demoService;
  }

  public record DeadlockRequest(String mode) {}

  public record SyncRequest(Integer num_threads, Integer increments_per_thread, Boolean use_mutex) {}

  @PostMapping(value = "/fcfs", consumes = {"application/json", "*/*"})
  public FcfsScheduleResponse fcfs(@RequestBody(required = false) List<FcfsJobInput> jobs) {
    return demoService.runFcfs(jobs);
  }

  @PostMapping(value = "/deadlock", consumes = {"application/json", "*/*"})
  public DeadlockDemoResponse deadlock(@RequestBody(required = false) DeadlockRequest request) {
    String mode = (request != null && request.mode() != null) ? request.mode() : "deadlock";
    return demoService.runDeadlock(mode);
  }

  @PostMapping(value = "/sync", consumes = {"application/json", "*/*"})
  public SyncRaceDemoResponse sync(@RequestBody(required = false) SyncRequest request) {
    int threads = (request != null && request.num_threads() != null) ? request.num_threads() : 10;
    int increments = (request != null && request.increments_per_thread() != null) ? request.increments_per_thread() : 5000;
    boolean useMutex = (request != null && request.use_mutex() != null) && request.use_mutex();
    return demoService.runSync(threads, increments, useMutex);
  }

  @PostMapping("/concurrency-race")
  public ConcurrencyTestResponse race(
      @RequestParam(value = "num_competing", defaultValue = "20") int numCompeting) {
    return demoService.runConcurrencyRace(numCompeting);
  }
}
