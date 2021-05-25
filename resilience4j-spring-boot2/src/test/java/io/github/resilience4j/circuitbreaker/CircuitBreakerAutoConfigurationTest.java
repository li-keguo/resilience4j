/*
 * Copyright 2017 Robert Winkler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.circuitbreaker;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.github.resilience4j.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.configure.CircuitBreakerAspect;
import io.github.resilience4j.common.circuitbreaker.monitoring.endpoint.CircuitBreakerEventDTO;
import io.github.resilience4j.common.circuitbreaker.monitoring.endpoint.CircuitBreakerEventsEndpointResponse;
import io.github.resilience4j.service.test.DummyService;
import io.github.resilience4j.service.test.ReactiveDummyService;
import io.github.resilience4j.service.test.TestApplication;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = TestApplication.class)
public class CircuitBreakerAutoConfigurationTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8090);
    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    CircuitBreakerProperties circuitBreakerProperties;
    @Autowired
    CircuitBreakerAspect circuitBreakerAspect;
    @Autowired
    DummyService dummyService;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ReactiveDummyService reactiveDummyService;

    /**
     * The test verifies that a CircuitBreaker instance is created and configured properly when the
     * DummyService is invoked and that the CircuitBreaker records successful and failed calls.
     */
    @Test
    public void testCircuitBreakerAutoConfiguration() throws IOException {
        assertThat(circuitBreakerRegistry).isNotNull();
        assertThat(circuitBreakerProperties).isNotNull();

        List<CircuitBreakerEventDTO> circuitBreakerEventsBefore = getCircuitBreakersEvents();
        List<CircuitBreakerEventDTO> circuitBreakerEventsForABefore = getCircuitBreakerEvents("backendA");

        try {
            dummyService.doSomething(true);
        } catch (IOException ex) {
            // Do nothing. The IOException is recorded by the CircuitBreaker as part of the recordFailurePredicate as a failure.
        }
        // The invocation is recorded by the CircuitBreaker as a success.
        dummyService.doSomething(false);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(DummyService.BACKEND);
        assertThat(circuitBreaker).isNotNull();

        // expect CircuitBreaker is configured as defined in application.yml
        assertThat(circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(6);
        assertThat(
            circuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
            .isEqualTo(2);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold())
            .isEqualTo(70f);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getWaitDurationInOpenState())
            .isEqualByComparingTo(Duration.ofSeconds(5L));

        // Create CircuitBreaker dynamically with default config
        CircuitBreaker dynamicCircuitBreaker = circuitBreakerRegistry
            .circuitBreaker("dynamicBackend");

        // expect circuitbreaker-event actuator endpoint recorded all events
        assertThat(getCircuitBreakersEvents())
            .hasSize(circuitBreakerEventsBefore.size() + 2);
        assertThat(getCircuitBreakerEvents("backendA"))
            .hasSize(circuitBreakerEventsForABefore.size() + 2);

        // expect no health indicator for backendB, as it is disabled via properties
        ResponseEntity<CompositeHealthResponse> healthResponse = restTemplate
            .getForEntity("/actuator/health/circuitBreakers", CompositeHealthResponse.class);
        assertThat(healthResponse.getBody().getDetails()).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("backendA")).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("backendB")).isNull();
        assertThat(healthResponse.getBody().getDetails().get("backendSharedA")).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("backendSharedB")).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("dynamicBackend")).isNotNull();

        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate()
            .test(new RecordedException())).isTrue();
        assertThat(circuitBreaker.getCircuitBreakerConfig().getIgnoreExceptionPredicate()
            .test(new IgnoredException())).isTrue();

        // Verify that an exception for which setRecordFailurePredicate returns false and it is not included in
        // setRecordExceptions evaluates to false.
        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate()
            .test(new Exception())).isFalse();

        assertThat(circuitBreakerAspect.getOrder()).isEqualTo(400);

        // expect all shared configs share the same values and are from the application.yml file
        CircuitBreaker sharedA = circuitBreakerRegistry.circuitBreaker("backendSharedA");
        CircuitBreaker sharedB = circuitBreakerRegistry.circuitBreaker("backendSharedB");
        CircuitBreaker backendB = circuitBreakerRegistry.circuitBreaker("backendB");
        CircuitBreaker backendC = circuitBreakerRegistry.circuitBreaker("backendC");

        Duration defaultWaitDuration = Duration.ofSeconds(10);
        float defaultFailureRate = 60f;
        int defaultPermittedNumberOfCallsInHalfOpenState = 10;
        int defaultRingBufferSizeInClosedState = 100;
        // test the customizer effect which overload the sliding widow size
        assertThat(backendC.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(100);

        assertThat(backendB.getCircuitBreakerConfig().getSlidingWindowType())
            .isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);

        assertThat(sharedA.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(6);
        assertThat(sharedA.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
            .isEqualTo(defaultPermittedNumberOfCallsInHalfOpenState);
        assertThat(sharedA.getCircuitBreakerConfig().getFailureRateThreshold())
            .isEqualTo(defaultFailureRate);
        assertThat(sharedA.getCircuitBreakerConfig().getWaitDurationInOpenState())
            .isEqualTo(defaultWaitDuration);

        assertThat(sharedB.getCircuitBreakerConfig().getSlidingWindowSize())
            .isEqualTo(defaultRingBufferSizeInClosedState);
        assertThat(sharedB.getCircuitBreakerConfig().getSlidingWindowType())
            .isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        assertThat(sharedB.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
            .isEqualTo(defaultPermittedNumberOfCallsInHalfOpenState);
        assertThat(sharedB.getCircuitBreakerConfig().getFailureRateThreshold())
            .isEqualTo(defaultFailureRate);
        assertThat(sharedB.getCircuitBreakerConfig().getWaitDurationInOpenState())
            .isEqualTo(defaultWaitDuration);

        assertThat(dynamicCircuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize())
            .isEqualTo(defaultRingBufferSizeInClosedState);
        assertThat(dynamicCircuitBreaker.getCircuitBreakerConfig()
            .getPermittedNumberOfCallsInHalfOpenState())
            .isEqualTo(defaultPermittedNumberOfCallsInHalfOpenState);
        assertThat(dynamicCircuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold())
            .isEqualTo(defaultFailureRate);
        assertThat(dynamicCircuitBreaker.getCircuitBreakerConfig().getWaitDurationInOpenState())
            .isEqualTo(defaultWaitDuration);
    }

    @Test
    public void shouldDefineWaitIntervalFunctionInOpenStateForCircuitBreakerAutoConfiguration() {
        //when
        final CircuitBreaker backendC = circuitBreakerRegistry.getAllCircuitBreakers()
            .filter(circuitBreaker -> circuitBreaker.getName().equalsIgnoreCase("backendC"))
            .get();
        //then
        assertThat(backendC).isNotNull();
        CircuitBreakerConfig backendConfig = backendC.getCircuitBreakerConfig();

        assertThat(backendConfig.getWaitIntervalFunctionInOpenState()).isNotNull();
        assertThat(backendConfig.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(1000);
        assertThat(backendConfig.getWaitIntervalFunctionInOpenState().apply(2)).isEqualTo(1111);
        assertThat(backendConfig.getWaitDurationInOpenState())
            .isEqualByComparingTo(Duration.ofSeconds(1L));
    }

    private List<CircuitBreakerEventDTO> getCircuitBreakersEvents() {
        return getEventsFrom("/actuator/circuitbreakerevents");
    }

    private List<CircuitBreakerEventDTO> getCircuitBreakerEvents(String name) {
        return getEventsFrom("/actuator/circuitbreakerevents/" + name);
    }

    private List<CircuitBreakerEventDTO> getEventsFrom(String path) {
        return restTemplate.getForEntity(path, CircuitBreakerEventsEndpointResponse.class)
            .getBody().getCircuitBreakerEvents();
    }
}
