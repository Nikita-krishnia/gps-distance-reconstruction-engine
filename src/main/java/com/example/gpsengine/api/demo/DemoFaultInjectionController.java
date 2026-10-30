package com.example.gpsengine.api.demo;

import com.example.gpsengine.routing.FakeRoutingProvider;
import com.example.gpsengine.routing.RoutingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller providing a runtime provider failure toggle strictly for automated smoke tests
 * and interactive evaluations.
 *
 * <p>SAFETY GUARDRAILS:
 * <ul>
 *   <li>Only active when {@code demo.fault-injection.enabled=true} AND {@code routing.provider=fake}.</li>
 *   <li>Never instantiated when {@code routing.provider=osrm}.</li>
 *   <li>Protected by Spring Security: accessible to {@code ADMIN} role only.</li>
 *   <li>Must remain disabled (default {@code false}) in any production environment.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/demo")
@ConditionalOnProperty(name = "demo.fault-injection.enabled", havingValue = "true")
@ConditionalOnProperty(name = "routing.provider", havingValue = "fake", matchIfMissing = true)
public class DemoFaultInjectionController {

    private static final Logger log = LoggerFactory.getLogger(DemoFaultInjectionController.class);

    private final FakeRoutingProvider fakeRoutingProvider;

    public DemoFaultInjectionController(@Qualifier("rawRoutingProvider") RoutingProvider rawProvider) {
        if (rawProvider instanceof FakeRoutingProvider fake) {
            this.fakeRoutingProvider = fake;
        } else {
            throw new IllegalStateException("Demo fault injection requires FakeRoutingProvider");
        }
        log.warn("demo fault injection is ENABLED");
    }

    public record ProviderFailureRequest(boolean failing) {}

    public record ProviderFailureResponse(boolean failing) {}

    @PostMapping("/provider-failure")
    public ResponseEntity<ProviderFailureResponse> toggleProviderFailure(@RequestBody ProviderFailureRequest request) {
        fakeRoutingProvider.setFailing(request.failing());
        log.info("Demo fault injection toggled: failing={}", request.failing());
        return ResponseEntity.ok(new ProviderFailureResponse(fakeRoutingProvider.isFailing()));
    }
}

