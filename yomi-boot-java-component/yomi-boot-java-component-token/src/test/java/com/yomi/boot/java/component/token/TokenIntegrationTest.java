package com.yomi.boot.java.component.token;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.token.config.TokenAutoConfiguration;
import com.yomi.boot.java.component.token.config.TokenProperties;
import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.exception.TokenErrorCode;
import com.yomi.boot.java.component.token.generator.UuidTokenGenerator;
import com.yomi.boot.java.component.token.service.TokenService;
import com.yomi.boot.java.component.token.session.TokenContext;
import com.yomi.boot.java.component.token.session.TokenSession;
import com.yomi.boot.java.component.token.store.TokenKeys;
import com.yomi.boot.java.component.token.store.TokenStore;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 令牌组件集成测试：真实 Redis + 真实 Redisson 锁 + 真实 MVC 装配
 * <p>
 * 覆盖组件关键行为：签发/校验/撤销/单点登录互踢/列表与陈旧索引清理/存储散列与 TTL、
 * 以及 Web 链路（跳过路径、401 渲染与处理器优先级、等锁超时 TOKEN_ISSUE_BUSY）。
 * 默认跳过；配置 {@code yomi.test.redis.host}（或环境变量 YOMI_REDIS_HOST 等）后执行。
 *
 * @author zwei
 */
@EnabledIf("redisConfigured")
class TokenIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private static AnnotationConfigWebApplicationContext context;

    private static MockMvc mockMvc;

    private static StringRedisTemplate redisTemplate;

    private static TokenKeys keys;

    private static TokenStore store;

    private static ITokenService tokenService;

    static boolean redisConfigured() {
        return RedisTestSettings.configured();
    }

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        Map<String, Object> tokenProps = new HashMap<>();
        tokenProps.put("yomi.token.enabled", "true");
        tokenProps.put("yomi.token.ttl", "10m");
        tokenProps.put("yomi.token.allow-multi-login", "false");
        tokenProps.put("yomi.token.store-prefix", "yomi:test:token:" + UUID.randomUUID());
        tokenProps.put("yomi.token.lock-wait", "500ms");
        tokenProps.put("yomi.token.skip-validate-paths[0]", "/api/login");
        tokenProps.put("yomi.token.skip-validate-paths[1]", "/api/public/**");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "token-integration-test", tokenProps));
        // SpringUtils 为组件内锁实现（LockUtils）取用 RedissonClient 的静态入口，需显式注册
        context.register(IntegrationConfig.class, TokenAutoConfiguration.class, SpringUtils.class);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        redisTemplate = context.getBean(StringRedisTemplate.class);
        keys = context.getBean(TokenKeys.class);
        store = context.getBean(TokenStore.class);
        tokenService = context.getBean(ITokenService.class);
    }

    @AfterAll
    static void tearDown() {
        if (redisTemplate != null && keys != null) {
            for (String pattern : keys.cleanupKeyPatterns()) {
                Set<String> created = redisTemplate.keys(pattern);
                if (CollectionUtils.isNotEmpty(created)) {
                    redisTemplate.delete(created);
                }
            }
        }
        if (context != null) {
            context.close();
        }
    }

    // ==================== 服务层：签发 / 校验 / 撤销 ====================

    @Test
    void issueFillsSessionDefaultsAndStoresTokenAsHash() {
        ITokenService service = multiLoginService();
        TokenSession session = new TokenSession();
        session.setUserId(1001L);

        String token = service.issue(session);

        assertFalse(token.isBlank());
        TokenSession stored = service.validate(token);
        assertEquals(1001L, stored.getUserId());
        assertEquals(stored.getIssuedAt().plus(TTL), stored.getExpireAt());
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(keys.sessionKey(1001L, token))));
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(keys.routeKey(token))));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.getPrefix() + ":" + token)), "明文令牌不应落键");
    }

    @Test
    void indexTtlOnlyExtendsWithSessionTtl() {
        store.save("ttl-short", sessionOf(1002L, Duration.ofMinutes(30)));
        Long first = redisTemplate.getExpire(keys.userTokensKey(1002L), TimeUnit.MILLISECONDS);
        assertTrue(first != null && first > 0, "索引 SET 应设置 TTL");

        store.save("ttl-shorter", sessionOf(1002L, Duration.ofMinutes(10)));
        Long afterShorter = redisTemplate.getExpire(keys.userTokensKey(1002L), TimeUnit.MILLISECONDS);
        assertTrue(afterShorter != null && afterShorter >= first - 1000, "索引 TTL 不应被较短的会话 TTL 缩短");

        store.save("ttl-longer", sessionOf(1002L, Duration.ofMinutes(60)));
        Long afterLonger = redisTemplate.getExpire(keys.userTokensKey(1002L), TimeUnit.MILLISECONDS);
        assertTrue(afterLonger != null && afterLonger > afterShorter, "索引 TTL 应被较长的会话 TTL 延长");
    }

    @Test
    void issueRejectsInvalidAndExpiredSession() {
        ITokenService service = multiLoginService();

        BizException invalid = assertThrows(BizException.class, () -> service.issue(new TokenSession()));
        assertEquals(TokenErrorCode.SESSION_INVALID.getCode(), invalid.getErrorCode());

        TokenSession expired = new TokenSession();
        expired.setUserId(1001L);
        expired.setExpireAt(Instant.now().minusSeconds(1));
        BizException expiredEx = assertThrows(BizException.class, () -> service.issue(expired));
        assertEquals(TokenErrorCode.SESSION_INVALID.getCode(), expiredEx.getErrorCode());

        TokenSession inverted = new TokenSession();
        inverted.setUserId(1001L);
        Instant now = Instant.now();
        inverted.setIssuedAt(now);
        inverted.setExpireAt(now.minusSeconds(1));
        BizException invertedEx = assertThrows(BizException.class, () -> service.issue(inverted));
        assertEquals(TokenErrorCode.SESSION_INVALID.getCode(), invertedEx.getErrorCode());
    }

    @Test
    void validateRejectsBlankUnknownAndExpiredToken() {
        ITokenService service = multiLoginService();

        assertEquals(TokenErrorCode.INVALID,
                assertThrows(TokenAuthException.class, () -> service.validate("  ")).getErrorCode());
        assertEquals(TokenErrorCode.INVALID,
                assertThrows(TokenAuthException.class, () -> service.validate("unknown-token")).getErrorCode());

        // 快照仍在 Redis（TTL 尚未到期）但 expireAt 已过，须由校验兜底拒绝并清理
        TokenSession shortLived = sessionOf(1003L, Duration.ofSeconds(1));
        String token = service.issue(shortLived);
        sleepMillis(1200);

        assertEquals(TokenErrorCode.INVALID,
                assertThrows(TokenAuthException.class, () -> service.validate(token)).getErrorCode());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.sessionKey(1003L, token))), "过期快照应被清理");
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.routeKey(token))), "过期路由应被清理");
    }

    @Test
    void multiLoginKeepsAllAndListSortedByIssuedAtDesc() {
        ITokenService service = multiLoginService();
        TokenSession older = new TokenSession();
        older.setUserId(2001L);
        older.setIssuedAt(Instant.now().minusSeconds(60));
        String olderToken = service.issue(older);
        String newerToken = service.issue(sessionOf(2001L, TTL));

        // 索引中混入无快照的陈旧成员，列表查询应清理
        String staleMember = TokenKeys.hashToken("stale-token");
        redisTemplate.opsForSet().add(keys.userTokensKey(2001L), staleMember);

        List<TokenSession> sessions = service.listByUserId(2001L);

        assertEquals(2, sessions.size());
        assertTrue(sessions.get(0).getIssuedAt().isAfter(sessions.get(1).getIssuedAt()), "应按签发时间倒序");
        assertNotEquals(olderToken, newerToken);
        assertFalse(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(keys.userTokensKey(2001L), staleMember)));
    }

    @Test
    void revokeByUserIdRevokesAllAndClearsIndex() {
        ITokenService service = multiLoginService();
        String first = service.issue(sessionOf(2002L, TTL));
        String second = service.issue(sessionOf(2002L, TTL));

        service.revokeByUserId(2002L);

        assertEquals(TokenErrorCode.INVALID,
                assertThrows(TokenAuthException.class, () -> service.validate(first)).getErrorCode());
        assertEquals(TokenErrorCode.INVALID,
                assertThrows(TokenAuthException.class, () -> service.validate(second)).getErrorCode());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.userTokensKey(2002L))));
        // 路由键无 hash tag（脚本访问不到），按撤销前成员尽力清理
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.routeKey(first))));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.routeKey(second))));
    }

    /**
     * 并发签发 × 按用户撤销：不变量"快照仍存活 ⇒ 必在用户索引里"必须始终成立。
     * <p>旧实现（Java 侧先读索引、删快照，再 DEL 索引）在两步之间遇到签发，会把新令牌留成
     * 撤销不掉、列表也看不到的漏网会话；本条在真 Redis 上反复交错以守住该不变量。</p>
     */
    @Test
    void concurrentIssueAndRevokeNeverLeavesIndexlessSession() throws Exception {
        ITokenService service = multiLoginService();
        long userId = 2006L;
        List<String> issued = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> issuer = pool.submit(() -> {
                for (int i = 0; i < 150; i++) {
                    issued.add(service.issue(sessionOf(userId, TTL)));
                }
            });
            Future<?> revoker = pool.submit(() -> {
                for (int i = 0; i < 150; i++) {
                    service.revokeByUserId(userId);
                }
            });
            issuer.get(30, TimeUnit.SECONDS);
            revoker.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            service.revokeByUserId(userId);
        }

        for (String token : issued) {
            if (store.load(token) != null) {
                assertTrue(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(
                                keys.userTokensKey(userId), TokenKeys.hashToken(token))),
                        "出现漏网会话：令牌有效但已脱离用户索引");
            }
        }
    }

    @Test
    void listByUserIdCleansCorruptSnapshotJson() {
        ITokenService service = multiLoginService();
        String token = service.issue(sessionOf(2004L, TTL));
        String storageHash = TokenKeys.hashToken(token);
        redisTemplate.opsForValue().set(keys.sessionKeyByStorageToken(2004L, storageHash), "{not-json");

        List<TokenSession> sessions = service.listByUserId(2004L);

        assertTrue(sessions.isEmpty());
        assertFalse(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(keys.userTokensKey(2004L), storageHash)));
    }

    @Test
    void revokeRemovesIndexMember() {
        ITokenService service = multiLoginService();
        String token = service.issue(sessionOf(2005L, TTL));
        String storageHash = TokenKeys.hashToken(token);

        service.revoke(token);

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.sessionKey(2005L, token))));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(keys.routeKey(token))));
        assertFalse(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(keys.userTokensKey(2005L), storageHash)));
    }

    @Test
    void revokeAndListRejectInvalidUserId() {
        BizException ex = assertThrows(BizException.class, () -> tokenService.revokeByUserId(0L));
        assertEquals(TokenErrorCode.INVALID_INPUT.getCode(), ex.getErrorCode());
        assertThrows(BizException.class, () -> tokenService.listByUserId(null));
    }

    // ==================== 滑动续期：绝对上限与"不复活"守卫 ====================

    @Test
    void renewIfPresentDoesNotResurrectRevokedSession() {
        ITokenService service = multiLoginService();
        String token = service.issue(sessionOf(2101L, TTL));
        TokenSession session = store.load(token);
        assertNotNull(session, "签发后快照应存在");

        service.revoke(token);

        assertNull(store.load(token), "撤销后快照应已删除");
        assertFalse(store.renewIfPresent(token, session), "续期守卫必须拒绝已不存在的会话");
        assertNull(store.load(token), "续期守卫不得复活已撤销的会话");
        assertTrue(store.listByUserId(2101L).isEmpty(), "用户索引不应回挂已撤销的 token");
    }

    @Test
    void renewIsCappedAtMaxLifetime() {
        TokenProperties properties = new TokenProperties();
        properties.setEnabled(true);
        properties.setTtl(TTL);
        properties.setAllowMultiLogin(true);
        properties.setRenewOnValidate(true);
        properties.setMaxLifetime(Duration.ofHours(1));
        properties.setStorePrefix(keys.getPrefix());
        ITokenService service = new TokenService(new UuidTokenGenerator(), store, properties, keys);

        // 会话 55 分钟前签发、还剩 2 分钟有效：now + 10m 会越过 issuedAt + 1h，续期应被钉在 1 小时
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofMinutes(55));
        TokenSession session = new TokenSession();
        session.setUserId(2102L);
        session.setIssuedAt(issuedAt);
        session.setExpireAt(Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofMinutes(2)));
        String token = service.issue(session);

        TokenSession validated = service.validate(token);

        assertEquals(issuedAt.plus(Duration.ofHours(1)), validated.getExpireAt(), "续期不得越过 issuedAt + max-lifetime");
        assertEquals(issuedAt.plus(Duration.ofHours(1)), store.load(token).getExpireAt(), "写回的也必须是上限值");
    }

    // ==================== Web 链路：拦截器 / 跳过路径 / 错误渲染 ====================

    @Test
    void skipPathAccessibleWithoutToken() throws Exception {
        assertEquals("pong", bodyOf(mockMvc.perform(get("/api/public/ping"))
                .andExpect(status().isOk())
                .andReturn()));
    }

    @Test
    void missingTokenRejectedWithTokenRequired() throws Exception {
        String body = bodyOf(mockMvc.perform(get("/api/me")).andExpect(status().isOk()).andReturn());

        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("\"errorCode\":\"TOKEN_REQUIRED\""), body);
    }

    @Test
    void loginThenAccessProtectedPathAndLogout() throws Exception {
        String token = login(3001L);

        assertEquals("3001", bodyOf(mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()));

        assertEquals("ok", bodyOf(mockMvc.perform(post("/api/logout")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn()));

        assertTrue(bodyOf(mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).contains("\"errorCode\":\"TOKEN_INVALID\""));
    }

    @Test
    void invalidTokenRejected() throws Exception {
        String body = bodyOf(mockMvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isOk())
                .andReturn());

        assertTrue(body.contains("\"errorCode\":\"TOKEN_INVALID\""), body);
    }

    @Test
    void singleLoginRevokesPreviousToken() throws Exception {
        String first = login(3002L);
        String second = login(3002L);

        assertNotEquals(first, second);
        assertTrue(bodyOf(mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk())
                .andReturn()).contains("\"errorCode\":\"TOKEN_INVALID\""));
        assertEquals("3002", bodyOf(mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk())
                .andReturn()));
    }

    @Test
    void issueBusyWhenLockHeldByAnotherThread() throws Exception {
        RedissonClient redissonClient = context.getBean(RedissonClient.class);
        RLock lock = redissonClient.getLock(keys.issueLockKey(3003L));
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // 另一线程持有用户维度签发锁（Redisson 锁按线程可重入，须跨线程才能造出竞争）
            holder.submit(() -> lock.lock(10, TimeUnit.SECONDS)).get(5, TimeUnit.SECONDS);

            String body = bodyOf(mockMvc.perform(post("/api/login").param("userId", "3003"))
                    .andExpect(status().isOk())
                    .andReturn());

            assertTrue(body.contains("\"errorCode\":\"TOKEN_ISSUE_BUSY\""), body);
        } finally {
            holder.submit(lock::unlock).get(5, TimeUnit.SECONDS);
            holder.shutdownNow();
        }
    }

    // ==================== 测试辅助 ====================

    /**
     * 多端并发登录配置的服务实例：服务层用例走无锁路径，避免与单点登录锁耦合
     */
    private static ITokenService multiLoginService() {
        TokenProperties properties = new TokenProperties();
        properties.setEnabled(true);
        properties.setTtl(TTL);
        properties.setAllowMultiLogin(true);
        properties.setStorePrefix(keys.getPrefix());
        return new TokenService(new UuidTokenGenerator(), store, properties, keys);
    }

    /** 按用户构造登录会话 */
    private static TokenSession sessionOf(Long userId, Duration ttl) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        TokenSession session = new TokenSession();
        session.setUserId(userId);
        session.setIssuedAt(now);
        session.setExpireAt(now.plus(ttl));
        return session;
    }

    /** 执行登录并返回令牌 */
    private String login(long userId) throws Exception {
        return bodyOf(mockMvc.perform(post("/api/login").param("userId", String.valueOf(userId)))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** 读取响应体字符串 */
    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 测试用休眠 */
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 集成测试装配：真实 Redis/Redisson + 令牌配置 + 示例登录接口 + 模拟框架全局异常处理器
     */
    @Configuration
    @EnableWebMvc
    static class IntegrationConfig {

        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            if (StringUtils.isNotBlank(RedisTestSettings.password())) {
                config.setPassword(RedisTestSettings.password());
            }
            config.useSingleServer()
                    .setAddress("redis://" + RedisTestSettings.host() + ":" + RedisTestSettings.port())
                    .setDatabase(RedisTestSettings.database());
            return Redisson.create(config);
        }

        @Bean
        LettuceConnectionFactory lettuceConnectionFactory() {
            RedisStandaloneConfiguration configuration =
                    new RedisStandaloneConfiguration(RedisTestSettings.host(), RedisTestSettings.port());
            if (StringUtils.isNotBlank(RedisTestSettings.password())) {
                configuration.setPassword(RedisPassword.of(RedisTestSettings.password()));
            }
            configuration.setDatabase(RedisTestSettings.database());
            return new LettuceConnectionFactory(configuration);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean
        DemoAuthController demoAuthController(ITokenService tokenService) {
            return new DemoAuthController(tokenService);
        }

        @Bean
        TestBizAdvice testBizAdvice() {
            return new TestBizAdvice();
        }

        @Bean
        TestFallbackAdvice testFallbackAdvice() {
            return new TestFallbackAdvice();
        }

    }

    /**
     * 示例登录/登出接口
     */
    @RestController
    static class DemoAuthController {

        private final ITokenService tokenService;

        DemoAuthController(ITokenService tokenService) {
            this.tokenService = tokenService;
        }

        @PostMapping("/api/login")
        String login(@RequestParam long userId) {
            TokenSession session = new TokenSession();
            session.setUserId(userId);
            session.setClientType("web");
            return tokenService.issue(session);
        }

        @PostMapping("/api/logout")
        String logout() {
            tokenService.revoke(TokenContext.tokenValue());
            return "ok";
        }

        @GetMapping("/api/me")
        String me() {
            return String.valueOf(TokenContext.requireOperatorId());
        }

        @GetMapping("/api/public/ping")
        String ping() {
            return "pong";
        }

    }

    /**
     * 模拟框架全局异常处理器对 {@link BizException} 的渲染（TOKEN_ISSUE_BUSY 等 601 错误码出口）
     */
    @RestControllerAdvice
    static class TestBizAdvice {

        @ExceptionHandler(BizException.class)
        @ResponseStatus(HttpStatus.OK)
        Response<Void> bizException(BizException ex) {
            return Response.fail(ex.getCode(), ex.getErrorCode(), ex.getMessage());
        }

    }

    /**
     * 模拟框架全局异常处理器的 Exception 兜底：若令牌鉴权异常未被组件处理器优先接管，
     * 401 断言将失败（该兜底会渲染成"服务器繁忙"），由此隐式验证处理器优先级
     */
    @RestControllerAdvice
    @Order(Ordered.LOWEST_PRECEDENCE)
    static class TestFallbackAdvice {

        @ExceptionHandler(Exception.class)
        @ResponseStatus(HttpStatus.OK)
        Response<Void> fallback() {
            return Response.fail(-1, null, "服务器繁忙，请稍后重试");
        }

    }

}
