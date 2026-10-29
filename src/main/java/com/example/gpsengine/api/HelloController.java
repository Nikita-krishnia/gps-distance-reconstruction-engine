package com.example.gpsengine.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HelloController {

    public record HelloResponse(String status, String service) {}

    @GetMapping("/hello")
    public HelloResponse hello() {
        return new HelloResponse("UP", "gps-distance-engine");
    }
}

