package com.mugsun.boot.app.dto;

import java.util.List;

public record AppPageVO<T>(List<T> records, long total) {
}
