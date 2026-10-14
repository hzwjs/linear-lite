package com.linearlite.server.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(value = "authorities", allowGetters = true, ignoreUnknown = true)
abstract class LinearLiteUserDetailsMixin {

    @JsonCreator
    LinearLiteUserDetailsMixin(
            @JsonProperty("userId") Long userId,
            @JsonProperty("username") String username,
            @JsonProperty("enabled") boolean enabled) {
    }
}
