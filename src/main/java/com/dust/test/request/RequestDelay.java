package com.dust.test.request;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Represents the RequestDelay class in the request project.
 *
 * @author Kashan Asim
 * @version 1.0
 * @project request
 * @module com.dust.test.request
 * @class RequestDelay
 * @lastModifiedBy Kashan.Asim
 * @lastModifiedDate 7/29/2025
 * @license Licensed under the Apache License, Version 2.0
 * @description A brief description of the class functionality.
 * @notes <ul>
 * <li>Provide any additional notes or remarks here.</li>
 * </ul>
 * @since 7/29/2025
 */
@RestController
@RequestMapping("/api/delay")
public class RequestDelay {
    @GetMapping("/{delay}/{count}")
    ResponseEntity getDelay(@PathVariable int delay, @PathVariable int count) throws InterruptedException {
        Thread.sleep(delay * 1000);

        if (Math.random() > 0.8)
            Thread.sleep(40000);

        System.out.println("Request received!\t" + count);
        return ResponseEntity.ok(count);
    }

    @PostMapping("/{delay}/{count}")
    ResponseEntity getDelayPost(@PathVariable int delay, @PathVariable int count) throws InterruptedException {
        Thread.sleep(delay * 1000);

        if (Math.random() > 0.8)
            Thread.sleep(10000);

        System.out.println("Request received!\t" + count);
        return ResponseEntity.ok(count);
    }
}
