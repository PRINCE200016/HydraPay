package com.hydrapay.ledger.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;

    @Value("${hydrapay.rate-limit.enabled:true}")
    private boolean enabled = true;

    @Value("${hydrapay.rate-limit.requests-per-second:100}")
    private long requestsPerSecond = 100;

    public boolean isAllowed(String clientIp) {
        if (!enabled) {
            return true;
        }

        try {
            long currentSecond = Instant.now().getEpochSecond();
            String key = "rate_limit:" + clientIp + ":" + currentSecond;

            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(10));
            }

            if (count != null && count > requestsPerSecond) {
                log.warn("Rate limit EXCEEDED for IP: {} (requests in current sec: {}, max: {})",
                        clientIp, count, requestsPerSecond);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Redis rate limiter encountered error, failing open for IP: {}", clientIp, e);
            return true;
        }
    }
}
