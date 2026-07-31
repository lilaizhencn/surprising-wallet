package com.surprising.wallet.common.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

/**
 * Jackson 3 JSON 操作工具。
 *
 * <p>统一封装对象树解析、对象转换和序列化，避免业务模块重复实现动态 JSON
 * 的边界处理。实际使用的 {@link ObjectMapper} 由 Spring Boot 注入，保证应用
 * 层复用统一的 Jackson 配置。
 */
public final class JacksonJson {

    /** 工具类不允许实例化。 */
    private JacksonJson() {
    }

    /**
     * 将 JSON 文本解析为对象节点。
     *
     * @param objectMapper Jackson 3 对象映射器
     * @param json         JSON 文本
     * @return 对象节点
     */
    public static ObjectNode readObject(ObjectMapper objectMapper, String json) {
        JsonNode node = objectMapper.readTree(json);
        return node == null ? null : node.asObject();
    }

    /**
     * 将 JSON 文本反序列化为指定类型。
     *
     * @param objectMapper Jackson 3 对象映射器
     * @param json         JSON 文本
     * @param type         目标类型
     * @param <T>          目标类型参数
     * @return 反序列化后的对象
     */
    public static <T> T readValue(ObjectMapper objectMapper, String json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }

    /**
     * 将 JSON 节点转换为指定类型。
     *
     * @param objectMapper Jackson 3 对象映射器
     * @param node         JSON 节点
     * @param type         目标类型
     * @param <T>          目标类型参数
     * @return 转换后的对象
     */
    public static <T> T toValue(ObjectMapper objectMapper, JsonNode node, Class<T> type) {
        return node == null || node.isNull() ? null : objectMapper.treeToValue(node, type);
    }

    /**
     * 将 JSON 数组节点转换为指定类型列表。
     *
     * @param objectMapper Jackson 3 对象映射器
     * @param node         JSON 数组节点
     * @param type         列表元素类型
     * @param <T>          列表元素类型参数
     * @return 转换后的列表；输入节点为空时返回 {@code null}
     */
    public static <T> List<T> toList(ObjectMapper objectMapper, JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<T> values = new ArrayList<>(node.size());
        for (JsonNode element : node.asArray().elements()) {
            values.add(objectMapper.treeToValue(element, type));
        }
        return values;
    }

    /**
     * 读取对象节点中的文本字段。
     *
     * @param objectNode 对象节点
     * @param field      字段名
     * @return 文本值；字段不存在或为 null 时返回 {@code null}
     */
    public static String text(ObjectNode objectNode, String field) {
        JsonNode value = objectNode.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 读取对象节点中的长整数字段。
     *
     * @param objectNode 对象节点
     * @param field      字段名
     * @return 长整数值；字段不存在或无法转换时返回 0
     */
    public static long longValue(ObjectNode objectNode, String field) {
        JsonNode value = objectNode.get(field);
        return value == null || value.isNull() ? 0L : value.asLong();
    }

    /**
     * 读取对象节点中的高精度数字字段。
     *
     * @param objectNode 对象节点
     * @param field      字段名
     * @return 高精度数字；字段不存在或为 null 时返回 {@code null}
     */
    public static BigDecimal decimalValue(ObjectNode objectNode, String field) {
        JsonNode value = objectNode.get(field);
        return value == null || value.isNull() ? null : value.asDecimal();
    }

    /**
     * 读取对象节点中的布尔字段。
     *
     * @param objectNode 对象节点
     * @param field      字段名
     * @return 布尔值；字段不存在或无法转换时返回 {@code false}
     */
    public static boolean booleanValue(ObjectNode objectNode, String field) {
        JsonNode value = objectNode.get(field);
        return value != null && !value.isNull() && value.asBoolean();
    }

    /**
     * 将对象序列化为 JSON 文本。
     *
     * @param objectMapper Jackson 3 对象映射器
     * @param value        待序列化对象
     * @return JSON 文本
     */
    public static String writeValue(ObjectMapper objectMapper, Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
