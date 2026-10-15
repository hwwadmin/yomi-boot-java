package com.yomi.boot.java.component.redis.ops.stream;

import com.yomi.boot.java.component.redis.config.RedisConfiguredCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

/**
 * Redis Stream 监听容器
 *
 * @author zwei
 */
@Configuration
@Conditional(RedisConfiguredCondition.class)
public class StreamConfiguration {

    /** Stream 阻塞读取超时 */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    /** 单次拉取条数 */
    private static final int BATCH_SIZE = 10;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> redisStreamListenerContainer(
            RedisConnectionFactory connectionFactory) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(POLL_TIMEOUT)
                        .batchSize(BATCH_SIZE)
                        .build();
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }

}
