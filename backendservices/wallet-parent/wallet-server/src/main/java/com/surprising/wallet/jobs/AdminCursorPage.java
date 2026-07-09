package com.surprising.wallet.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdminCursorPage {

    private AdminCursorPage() {
    }

    static int limit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }

    static SortSpec parseSort(String sort, SortSpec defaultSort, List<SortSpec> allowed) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String normalized = sort.trim();
        return allowed.stream()
                .filter(spec -> spec.token().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unsupported sort: " + normalized));
    }

    static List<SortSpec> timestampSorts(String field, String column, String responseKey,
                                         String idColumn, String idResponseKey) {
        return List.of(
                new SortSpec(field, column, responseKey, idColumn, idResponseKey, true),
                new SortSpec(field, column, responseKey, idColumn, idResponseKey, false));
    }

    static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor", ex);
        }
    }

    static void addSeekCondition(StringBuilder sql, List<Object> args, SortSpec sortSpec, Cursor cursor) {
        if (cursor == null) {
            return;
        }
        String operator = sortSpec.descending() ? "<" : ">";
        sql.append(" and (")
                .append(sortSpec.column()).append(' ').append(operator).append(" ?")
                .append(" or (").append(sortSpec.column()).append(" = ? and ")
                .append(sortSpec.idColumn()).append(' ').append(operator).append(" ?))");
        args.add(cursor.timestamp());
        args.add(cursor.timestamp());
        args.add(cursor.id());
    }

    static String orderBy(SortSpec sortSpec) {
        String direction = sortSpec.descending() ? "desc" : "asc";
        return sortSpec.column() + " " + direction + ", " + sortSpec.idColumn() + " " + direction;
    }

    static Map<String, Object> page(String listKey, List<Map<String, Object>> rows,
                                    int limit, SortSpec sortSpec, String... internalKeys) {
        boolean hasMore = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>(hasMore ? rows.subList(0, limit) : rows);
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Map<String, Object> last = items.get(items.size() - 1);
            nextCursor = encodeCursor(instant(last.get(sortSpec.responseKey())), longValue(last.get(sortSpec.idResponseKey())));
        }
        if (internalKeys != null && internalKeys.length > 0) {
            items = items.stream()
                    .map(row -> withoutKeys(row, internalKeys))
                    .toList();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put(listKey, items);
        payload.put("items", items);
        payload.put("nextCursor", nextCursor);
        payload.put("hasMore", hasMore);
        payload.put("sort", sortSpec.token());
        payload.put("limit", limit);
        return payload;
    }

    private static Map<String, Object> withoutKeys(Map<String, Object> source, String... keys) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        for (String key : keys) {
            copy.remove(key);
        }
        return copy;
    }

    private static String encodeCursor(Instant timestamp, long id) {
        String raw = timestamp.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "missing cursor timestamp");
        }
        return Instant.parse(String.valueOf(value));
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "missing cursor id");
        }
        return Long.parseLong(String.valueOf(value));
    }

    record SortSpec(String field, String column, String responseKey, String idColumn, String idResponseKey,
                    boolean descending) {
        String token() {
            return field + "." + (descending ? "desc" : "asc");
        }
    }

    record Cursor(Instant timestamp, long id) {
    }
}
