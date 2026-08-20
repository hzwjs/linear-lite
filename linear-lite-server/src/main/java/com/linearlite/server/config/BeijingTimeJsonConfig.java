package com.linearlite.server.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * API 中的绝对时间必须显式标记北京时间，禁止继续输出无时区的 LocalDateTime 字符串。
 */
@Configuration
public class BeijingTimeJsonConfig {
    private static final ZoneOffset BEIJING_OFFSET = ZoneOffset.ofHours(8);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer beijingTimeCustomizer() {
        JsonSerializer<LocalDateTime> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(value.atOffset(BEIJING_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        };
        JsonDeserializer<LocalDateTime> deserializer = new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                try {
                    OffsetDateTime value = OffsetDateTime.parse(parser.getText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    if (!BEIJING_OFFSET.equals(value.getOffset())) {
                        throw InvalidFormatException.from(parser, "时间必须使用北京时间 +08:00", parser.getText(), OffsetDateTime.class);
                    }
                    return value.toLocalDateTime();
                } catch (java.time.format.DateTimeParseException e) {
                    throw InvalidFormatException.from(parser, "时间必须是带 +08:00 偏移量的 ISO-8601 时间", parser.getText(), LocalDateTime.class);
                }
            }
        };
        // 只覆盖 LocalDateTime 的绝对时间规则，保留 Spring Boot 自动注册的 JavaTimeModule，
        // 这样 LocalDate、LocalTime 等日期类型仍由 Jackson 原生处理。
        return builder -> builder
                .serializerByType(LocalDateTime.class, serializer)
                .deserializerByType(LocalDateTime.class, deserializer);
    }
}
