package com.lbz.f1aipredict.prediction.dto.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 将 JSON 字符串反序列化为 UTC {@link Instant}，且强制要求带时区的 ISO-8601。
 * <p>
 * 仓库约定：带偏移的时间必须 {@code OffsetDateTime.parse(...).toInstant()}，
 * 不能直接 {@code Instant.parse}，也不能接受无时区的本地日期时间。
 * 无时区或无法解析的输入抛出 {@link JsonMappingException}。
 */
public class OffsetInstantDeserializer extends JsonDeserializer<Instant> {

    /**
     * 解析带时区的 ISO-8601 文本为 UTC Instant。
     *
     * @param parser JSON 解析器
     * @param context 反序列化上下文
     * @return UTC Instant；JSON null 返回 null，由后续 Bean Validation 处理必填
     */
    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // 必须带时区/偏移；无时区的 "2026-08-28T10:00:00" 会在此失败
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ex) {
            throw JsonMappingException.from(parser,
                    "dataCutoff must be ISO-8601 with timezone offset", ex);
        }
    }
}
