package com.nexcode.see.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

@RestController
@Slf4j
@EnableScheduling
public class SSEController {

	private final Map<String, ConnectionInfo> sessionConnections;

	public SSEController() {
		this.sessionConnections = new ConcurrentHashMap<>();
	}

	@GetMapping(value = "/subscribe/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> subscribe(@PathVariable String sessionId) {
		ConnectionInfo connectionInfo = new ConnectionInfo();
		connectionInfo.setLastActiveTimestamp(Instant.now());
		sessionConnections.put(sessionId, connectionInfo);

		Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
		Flux<ServerSentEvent<String>> eventFlux = sink.asFlux()
				.doOnSubscribe(subscription -> connectionInfo.setActive(true)).doFinally(signalType -> {
					connectionInfo.setActive(false);
					removeSession(sessionId);
				}).share(); // Share the eventFlux among subscribers

		connectionInfo.setEventSink(sink); // Set the sink in ConnectionInfo

		return eventFlux;
	}

	@PostMapping("/sendEvent/{sessionId}")
	public void sendEvent(@PathVariable String sessionId, @RequestBody String eventData) {
		ConnectionInfo connectionInfo = sessionConnections.get(sessionId);

		if (connectionInfo != null) {
			connectionInfo.setLastActiveTimestamp(Instant.now());

			Many<ServerSentEvent<String>> sink = connectionInfo.getEventSink();

			if (sink != null) {
				ServerSentEvent<String> event = ServerSentEvent.<String>builder().id(generateEventId())
						.event("event-type").data(eventData).build();
				sink.emitNext(event, Sinks.EmitFailureHandler.FAIL_FAST);
			}
		}
	}

	@Scheduled(fixedDelay = 50000) // Check for idle connections every 5 seconds
	private void checkIdleConnections() {
		for (Map.Entry<String, ConnectionInfo> entry : sessionConnections.entrySet()) {
			String sessionId = entry.getKey();
			ConnectionInfo connectionInfo = entry.getValue();

			if (!connectionInfo.isActive() && isConnectionIdle(sessionId)) {
				removeSession(sessionId);
			}
		}
	}

	private boolean isConnectionIdle(String sessionId) {
		ConnectionInfo connectionInfo = sessionConnections.get(sessionId);

		if (connectionInfo != null) {
			Instant lastActiveTimestamp = connectionInfo.getLastActiveTimestamp();
			Duration idleDuration = Duration.between(lastActiveTimestamp, Instant.now());
			Duration idleThreshold = Duration.ofMinutes(3); // Adjust the idle threshold as needed

			return idleDuration.compareTo(idleThreshold) >= 0;
		}

		return false;
	}

	private void removeSession(String sessionId) {
		sessionConnections.remove(sessionId);
	}

	private String generateEventId() {
		// Implement your event ID generation logic
		return UUID.randomUUID().toString();
	}

	private static class ConnectionInfo {
		private volatile boolean active;
		private Instant lastActiveTimestamp;
		private Many<ServerSentEvent<String>> eventSink;

		public boolean isActive() {
			return active;
		}

		public void setActive(boolean active) {
			this.active = active;
		}

		public Instant getLastActiveTimestamp() {
			return lastActiveTimestamp;
		}

		public void setLastActiveTimestamp(Instant lastActiveTimestamp) {
			this.lastActiveTimestamp = lastActiveTimestamp;
		}

		public Many<ServerSentEvent<String>> getEventSink() {
			return eventSink;
		}

		public void setEventSink(Many<ServerSentEvent<String>> eventSink) {
			this.eventSink = eventSink;
		}

	}

}
