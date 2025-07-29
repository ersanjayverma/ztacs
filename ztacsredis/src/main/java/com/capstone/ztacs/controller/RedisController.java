package com.capstone.ztacsredis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/redis")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class RedisController {

    private final StringRedisTemplate redisTemplate;

    // =========================
    // VALUE (String Key-Value)
    // =========================

    @Operation(summary = "Set a string key-value pair")
    @PostMapping("/set")
    public String set(@RequestParam String key, @RequestParam String value) {
        redisTemplate.opsForValue().set(key, value);
        return "Set key: " + key;
    }

    @Operation(summary = "Get value by key")
    @GetMapping("/get")
    public String get(@RequestParam String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Operation(summary = "Delete a key")
    @DeleteMapping("/delete")
    public String delete(@RequestParam String key) {
        redisTemplate.delete(key);
        return "Deleted key: " + key;
    }

    @Operation(summary = "List all keys (pattern supported)")
    @GetMapping("/keys")
    public Set<String> keys(@RequestParam(defaultValue = "*") String pattern) {
        return redisTemplate.keys(pattern);
    }

    // =========================
    // LIST operations
    // =========================

    @Operation(summary = "Left push to list")
    @PostMapping("/list/lpush")
    public Long lpush(@RequestParam String key, @RequestParam String value) {
        return redisTemplate.opsForList().leftPush(key, value);
    }

    @Operation(summary = "Right push to list")
    @PostMapping("/list/rpush")
    public Long rpush(@RequestParam String key, @RequestParam String value) {
        return redisTemplate.opsForList().rightPush(key, value);
    }

    @Operation(summary = "Left pop from list")
    @PostMapping("/list/lpop")
    public String lpop(@RequestParam String key) {
        return redisTemplate.opsForList().leftPop(key);
    }

    @Operation(summary = "Right pop from list")
    @PostMapping("/list/rpop")
    public String rpop(@RequestParam String key) {
        return redisTemplate.opsForList().rightPop(key);
    }

    @Operation(summary = "Get range from list")
    @GetMapping("/list/range")
    public List<String> listRange(@RequestParam String key,
                                  @RequestParam(defaultValue = "0") long start,
                                  @RequestParam(defaultValue = "-1") long end) {
        return redisTemplate.opsForList().range(key, start, end);
    }

    @Operation(summary = "Get list size")
    @GetMapping("/list/size")
    public Long listSize(@RequestParam String key) {
        return redisTemplate.opsForList().size(key);
    }

    // =========================
    // SET operations
    // =========================

    @Operation(summary = "Add value to set")
    @PostMapping("/set/add")
    public Long setAdd(@RequestParam String key, @RequestParam String value) {
        return redisTemplate.opsForSet().add(key, value);
    }

    @Operation(summary = "Get all set members")
    @GetMapping("/set/members")
    public Set<String> setMembers(@RequestParam String key) {
        return redisTemplate.opsForSet().members(key);
    }

    @Operation(summary = "Remove value from set")
    @DeleteMapping("/set/remove")
    public Long setRemove(@RequestParam String key, @RequestParam String value) {
        return redisTemplate.opsForSet().remove(key, value);
    }

    // =========================
    // HASH operations
    // =========================

    @Operation(summary = "Put entry in hash")
    @PostMapping("/hash/put")
    public void hashPut(@RequestParam String key,
                        @RequestParam String hashKey,
                        @RequestParam String value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    @Operation(summary = "Get value from hash")
    @GetMapping("/hash/get")
    public Object hashGet(@RequestParam String key,
                          @RequestParam String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    @Operation(summary = "Get all hash entries")
    @GetMapping("/hash/entries")
    public Map<Object, Object> hashEntries(@RequestParam String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    @Operation(summary = "Delete entry from hash")
    @DeleteMapping("/hash/delete")
    public Long hashDelete(@RequestParam String key, @RequestParam String hashKey) {
        return redisTemplate.opsForHash().delete(key, hashKey);
    }

    // =========================
    // ZSET (Sorted Set) operations
    // =========================

    @Operation(summary = "Add value with score to ZSET")
    @PostMapping("/zset/add")
    public Boolean zsetAdd(@RequestParam String key,
                           @RequestParam String value,
                           @RequestParam double score) {
        return redisTemplate.opsForZSet().add(key, value, score);
    }

    @Operation(summary = "Get ZSET range")
    @GetMapping("/zset/range")
    public Set<String> zsetRange(@RequestParam String key,
                                 @RequestParam(defaultValue = "0") long start,
                                 @RequestParam(defaultValue = "-1") long end) {
        return redisTemplate.opsForZSet().range(key, start, end);
    }

    @Operation(summary = "Get score of member in ZSET")
    @GetMapping("/zset/score")
    public Double zsetScore(@RequestParam String key,
                            @RequestParam String member) {
        return redisTemplate.opsForZSet().score(key, member);
    }

    @Operation(summary = "Remove member from ZSET")
    @DeleteMapping("/zset/remove")
    public Long zsetRemove(@RequestParam String key, @RequestParam String value) {
        return redisTemplate.opsForZSet().remove(key, value);
    }
}
