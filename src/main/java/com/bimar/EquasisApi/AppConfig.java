package com.bimar.EquasisApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // Add converters to handle different types of content
        restTemplate.getMessageConverters().add(new FormHttpMessageConverter()); // For handling form submissions
        restTemplate.getMessageConverters().add(new StringHttpMessageConverter()); // For handling plain text responses

        return restTemplate;
    }
}
