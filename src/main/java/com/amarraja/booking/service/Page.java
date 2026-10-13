package com.amarraja.booking.service;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long totalItems) {

    public int totalPages() {
        return (int) ((totalItems + size - 1) / size);
    }
}
