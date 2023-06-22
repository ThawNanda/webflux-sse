package com.nexcode.see.controller;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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

import com.nexcode.see.event.SseEvent;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

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
		log.info("subscribing........");
		Flux<ServerSentEvent<String>> eventFlux = Flux.interval(Duration.ofSeconds(3))
				.map(sequence -> "Heartbeat at " + Instant.now())
				.map(eventData -> ServerSentEvent.<String>builder().id(generateEventId())
						.event(SseEvent.HEART_BEAT_EVENT.name()).data(eventData).build())
				.doOnSubscribe(subscription -> connectionInfo.setActive(true)).doFinally(signalType -> {
					connectionInfo.setActive(false);
					removeSession(sessionId);
				});
		log.info("subscribed........");

		return eventFlux;
	}

	@PostMapping("/sendEvent/{sessionId}")
	public void sendEvent(@PathVariable String sessionId, @RequestBody String eventData) {
		ConnectionInfo connectionInfo = sessionConnections.get(sessionId);
		if (connectionInfo != null) {
			connectionInfo.setLastActiveTimestamp(Instant.now());

			FluxSink<ServerSentEvent<String>> eventSink = connectionInfo.getEventSink();
			if (eventSink != null) {
				ServerSentEvent<String> event = ServerSentEvent.<String>builder().id(generateEventId())
						.event("event-type").data(eventData).build();
				eventSink.next(event);
			}
		}
	}

	@Scheduled(fixedDelay = 300000) // Check for idle connections every 5 minutes
	private void checkIdleConnections() {

		System.out.println(sessionConnections.size());
		for (Map.Entry<String, ConnectionInfo> entry : sessionConnections.entrySet()) {
			String sessionId = entry.getKey();
			ConnectionInfo connectionInfo = entry.getValue();

			if (!connectionInfo.isActive() && isConnectionIdle(sessionId)) {
				removeSession(sessionId);
			}
		}
		System.out.println(sessionConnections.size());
	}

	private boolean isConnectionIdle(String sessionId) {
		ConnectionInfo connectionInfo = sessionConnections.get(sessionId);
		if (connectionInfo != null) {
			Instant lastActiveTimestamp = connectionInfo.getLastActiveTimestamp();
			log.info(lastActiveTimestamp.toString());
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
		private FluxSink<ServerSentEvent<String>> eventSink;

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

		public FluxSink<ServerSentEvent<String>> getEventSink() {
			return eventSink;
		}

		public void setEventSink(FluxSink<ServerSentEvent<String>> eventSink) {
			this.eventSink = eventSink;
		}
	}

}
