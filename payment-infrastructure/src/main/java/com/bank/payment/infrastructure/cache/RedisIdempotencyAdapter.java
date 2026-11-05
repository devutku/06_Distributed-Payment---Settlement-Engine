package com.bank.payment.infrastructure.cache;

import com.bank.payment.application.dto.TransferResultDto;
import com.bank.payment.application.port.out.IdempotencyPort;
import com.bank.payment.domain.model.transfer.IdempotencyKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyAdapter.class);
    private static final String LOCK_PREFIX = "idemp:lock:";
    private static final String CACHE_PREFIX = "idemp:resp:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // In-memory fallback if Redis is temporarily unreachable
    private final Map<String, String> localMemoryCache = new ConcurrentHashMap<>();
    private final Map<String, Long> localMemoryLocks = new ConcurrentHashMap<>();

    public RedisIdempotencyAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean acquireLock(IdempotencyKey key) {
        String lockKey = LOCK_PREFIX + key.value();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            log.warn("Redis acquireLock failed ({}), falling back to in-memory lock", ex.getMessage());
            long now = System.currentTimeMillis();
            Long existingLock = localMemoryLocks.putIfAbsent(key.value(), now);
            if (existingLock != null && (now - existingLock) < 30000) {
                return false;
            }
            localMemoryLocks.put(key.value(), now);
            return true;
        }
    }

    @Override
    public void releaseLock(IdempotencyKey key) {
        try {
            redisTemplate.delete(LOCK_PREFIX + key.value());
        } catch (Exception ex) {
            log.warn("Redis releaseLock failed ({})", ex.getMessage());
        } finally {
            localMemoryLocks.remove(key.value());
        }
    }

    @Override
    public Optional<TransferResultDto> getCachedResult(IdempotencyKey key) {
        String cacheKey = CACHE_PREFIX + key.value();
        String json = null;
        try {
            json = redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception ex) {
            log.warn("Redis getCachedResult failed ({}), checking in-memory cache", ex.getMessage());
            json = localMemoryCache.get(key.value());
        }

        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(json, TransferResultDto.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize TransferResultDto from cache", e);
            return Optional.empty();
        }
    }

    @Override
    public void cacheResult(IdempotencyKey key, TransferResultDto result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            try {
                redisTemplate.opsForValue().set(CACHE_PREFIX + key.value(), json, CACHE_TTL);
            } catch (Exception ex) {
                log.warn("Redis cacheResult failed ({}), saving to in-memory cache", ex.getMessage());
            }
            localMemoryCache.put(key.value(), json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TransferResultDto for cache", e);
        }
    }
}

