package com.kb.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录限流：同一账号或同一 IP 连续失败 max-fails 次后锁定 lock-seconds 秒；登录成功清零。
 * 单实例部署，计数保存在内存中（重启清零）。
 */
@Service
public class LoginGuard {

    private static final int MAX_KEYS = 50000;

    private static final class Entry {
        int fails;
        long lockedUntil;
    }

    private final Map<String, Entry> map = new ConcurrentHashMap<>();
    private final int maxFails;
    private final long lockMs;

    public LoginGuard(@Value("${kb.login.max-fails:5}") int maxFails,
                      @Value("${kb.login.lock-seconds:600}") long lockSeconds) {
        this.maxFails = maxFails;
        this.lockMs = lockSeconds * 1000;
    }

    /** 若账号或 IP 处于锁定中，返回剩余秒数；否则返回 0 */
    public long lockedSeconds(String username, String ip) {
        long now = System.currentTimeMillis();
        long left = 0;
        for (String k : keys(username, ip)) {
            Entry e = map.get(k);
            if (e != null && e.lockedUntil > now) {
                left = Math.max(left, (e.lockedUntil - now + 999) / 1000);
            }
        }
        return left;
    }

    /** 记录一次失败；达到阈值时加锁。返回 true 表示本次失败导致锁定 */
    public boolean fail(String username, String ip) {
        long now = System.currentTimeMillis();
        if (map.size() > MAX_KEYS) {
            map.values().removeIf(e -> e.lockedUntil < now && e.fails == 0);
            if (map.size() > MAX_KEYS) map.clear();
        }
        boolean locked = false;
        for (String k : keys(username, ip)) {
            Entry e = map.computeIfAbsent(k, x -> new Entry());
            synchronized (e) {
                if (e.lockedUntil != 0 && e.lockedUntil <= now) {   // 上一次锁定已过期，重新计数
                    e.fails = 0;
                    e.lockedUntil = 0;
                }
                if (++e.fails >= maxFails) {
                    e.lockedUntil = now + lockMs;
                    e.fails = 0;
                    locked = true;
                }
            }
        }
        return locked;
    }

    /** 登录成功：账号和 IP 的失败计数清零 */
    public void success(String username, String ip) {
        for (String k : keys(username, ip)) {
            map.remove(k);
        }
    }

    public long lockMinutes() {
        return Math.max(1, lockMs / 60000);
    }

    private static String[] keys(String username, String ip) {
        String u = username == null ? "" : username.trim().toLowerCase();
        return u.isEmpty() ? new String[]{"ip:" + ip} : new String[]{"u:" + u, "ip:" + ip};
    }
}
