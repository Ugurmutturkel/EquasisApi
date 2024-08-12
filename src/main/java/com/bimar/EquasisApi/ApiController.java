package com.bimar.EquasisApi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired
    private RestTemplate restTemplate;

    @PostMapping("/forward")
    public ResponseEntity<String> forwardPostRequest(@RequestBody LoginRequest loginRequest) {
        String url = "https://www.equasis.org/EquasisWeb/authen/HomePage";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        // Construct the body of the POST request
        String requestBody = "fs=HomePage&j_email=" + loginRequest.getEmail() +
                             "&j_password=" + loginRequest.getPassword() +
                             "&submit=Login";
        
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        // Send the POST request to Equasis
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to forward request: " + e.getMessage());
        }
        
        // Extract the JSESSIONID from the response headers
        String jsessionid = null;
        if (response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)) {
            String setCookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            if (setCookieHeader != null && setCookieHeader.contains("JSESSIONID")) {
                String[] cookies = setCookieHeader.split(";");
                for (String cookie : cookies) {
                    if (cookie.trim().startsWith("JSESSIONID")) {
                        jsessionid = cookie.split("=")[1].trim();
                        break;
                    }
                }
            }
        }

        // Return the JSESSIONID or a message indicating its absence
        if (jsessionid != null) {
            return ResponseEntity.ok("JSESSIONID: " + jsessionid);
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                 .body("JSESSIONID not found in the response.");
        }
    }
}
