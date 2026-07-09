package com.surprising.wallet.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCursorPageTest {

    @Test
    void pageBuildsCursorFromLastReturnedRow() {
        AdminCursorPage.SortSpec sort = new AdminCursorPage.SortSpec(
                "updatedAt", "updated_at", "updated_at", "id", "id", true);
        Map<String, Object> first = row("id", 12L, "updated_at", "2026-07-02T01:00:00Z");
        Map<String, Object> second = row("id", 11L, "updated_at", "2026-07-02T00:59:00Z");

        Map<String, Object> page = AdminCursorPage.page("rows", List.of(first, second), 1, sort);

        assertEquals(1, page.get("count"));
        assertTrue((Boolean) page.get("hasMore"));
        assertNotNull(page.get("nextCursor"));
        AdminCursorPage.Cursor decoded = AdminCursorPage.decodeCursor(String.valueOf(page.get("nextCursor")));
        assertEquals(12L, decoded.id());
    }

    @Test
    void parseSortRejectsUnsupportedSort() {
        AdminCursorPage.SortSpec defaultSort = new AdminCursorPage.SortSpec(
                "updatedAt", "updated_at", "updated_at", "id", "id", true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> AdminCursorPage.parseSort("amount.desc", defaultSort, List.of(defaultSort)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }
}
