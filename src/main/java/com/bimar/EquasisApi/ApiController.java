package com.bimar.EquasisApi;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private SessionService sessionService;

    @PostMapping("/login")
    public ResponseEntity<String> loginAndGetSessionId(@RequestBody LoginRequest loginRequest) {
        String url = "https://www.equasis.org/EquasisWeb/authen/HomePage";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String requestBody = "fs=HomePage&j_email=" + loginRequest.getEmail() +
                             "&j_password=" + loginRequest.getPassword() +
                             "&submit=Login";
        
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to forward request: " + e.getMessage());
        }
        
        String jsessionid = extractJSessionId(response.getHeaders());
        sessionService.setJsessionid(jsessionid);

        if (jsessionid != null) {
            return ResponseEntity.ok("JSESSIONID: " + jsessionid);
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                 .body("JSESSIONID not found in the response.");
        }
    }

    @PostMapping("/searchimo")
    public ResponseEntity<String> performSearch(@RequestBody SearchRequest searchRequest) {
        String jsessionid = sessionService.getJsessionid();
        if (jsessionid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                 .body("JSESSIONID is missing. Please log in first.");
        }

        String url = "https://www.equasis.org/EquasisWeb/restricted/Search?fs=HomePage";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);

        String requestBody = "P_PAGE=1&P_PAGE_COMP=1&P_PAGE_SHIP=1" +
                             "&P_ENTREE_HOME=" + searchRequest.getSearchInput() +
                             "&P_ENTREE_HOME_HIDDEN=" + searchRequest.getSearchInput() +
                             "&checkbox-ship=Ship&advancedSearch=";
                             
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            parseCompanyInfoAndWriteToCsv(response.getBody());
            parseHtmlAndWriteToCsv(response.getBody());
            simulateClickAndGetDetails(response.getBody());  // New method to simulate the click and get detailed info
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to perform search: " + e.getMessage());
        }

        return ResponseEntity.ok(response.getBody());
    }

    @PostMapping("/searchcompany")
    public ResponseEntity<String> performSearchComp(@RequestBody SearchRequest searchRequest) {
        String jsessionid = sessionService.getJsessionid();
        if (jsessionid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                 .body("JSESSIONID is missing. Please log in first.");
        }

        String url = "https://www.equasis.org/EquasisWeb/restricted/Search?fs=HomePage";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);

        String requestBody = "P_PAGE=1&P_PAGE_COMP=1&P_PAGE_SHIP=1" +
                             "&P_ENTREE_HOME=" + searchRequest.getSearchInput() +
                             "&P_ENTREE_HOME_HIDDEN=" + searchRequest.getSearchInput() +
                             "&checkbox-companySearch=Company" +
                             "&advancedSearch=";  

        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            parseHtmlAndWriteToCsv(response.getBody());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to perform search: " + e.getMessage());
        }

        return ResponseEntity.ok(response.getBody());
    }

    private String extractJSessionId(HttpHeaders headers) {
        String jsessionid = null;
        if (headers.containsKey(HttpHeaders.SET_COOKIE)) {
            String setCookieHeader = headers.getFirst(HttpHeaders.SET_COOKIE);
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
        return jsessionid;
    }

    private void parseHtmlAndWriteToCsv(String html) {
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        try (PrintWriter writer = new PrintWriter(new FileWriter("ships.csv"))) {
            writer.println("IMO number,Name of ship,Gross tonnage,Type of ship,Year of build,Flag");

            for (Element row : rows) {
                String imoNumber = row.select("th").text().trim();
                Elements dataCells = row.select("td");

                String nameOfShip = dataCells.size() > 0 ? dataCells.get(0).text().trim() : "";
                String grossTonnage = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
                String typeOfShip = dataCells.size() > 2 ? dataCells.get(2).text().trim() : "";
                String yearOfBuild = dataCells.size() > 3 ? dataCells.get(3).text().trim() : "";
                String flag = dataCells.size() > 4 ? dataCells.get(4).text().trim() : "";

                writer.printf("%s,%s,%s,%s,%s,%s%n", imoNumber, nameOfShip, grossTonnage, typeOfShip, yearOfBuild, flag);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseCompanyInfoAndWriteToCsv(String html) {
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        try (PrintWriter writer = new PrintWriter(new FileWriter("company_info.csv"))) {
            writer.println("IMO number,Company name,Date of effect");

            for (Element row : rows) {
                Elements cells = row.select("td");

                String imoNumber = cells.size() > 0 ? cells.get(0).text().trim() : "";
                String companyName = cells.size() > 2 ? cells.get(2).text().trim() : "";
                String dateOfEffect = cells.size() > 4 ? cells.get(4).text().trim() : "";

                writer.printf("%s,%s,%s%n", imoNumber, companyName, dateOfEffect);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // New methods to simulate click and get detailed information

    private void simulateClickAndGetDetails(String html) {
        Document doc = Jsoup.parse(html);
        Elements links = doc.select("a[onclick]");

        for (Element link : links) {
            String onclick = link.attr("onclick");

            // Extract IMO number from onclick attribute
            String imoNumber = extractImoFromOnclick(onclick);

            // Now simulate clicking by submitting the form with the IMO number
            if (imoNumber != null) {
                submitFormWithImo(imoNumber);
            }
        }
    }

    private String extractImoFromOnclick(String onclick) {
        // This is a simple regex to extract IMO number from onclick attribute
        Pattern pattern = Pattern.compile("\\d{7}"); // IMO number is typically 7 digits
        Matcher matcher = pattern.matcher(onclick);

        if (matcher.find()) {
            return matcher.group(0); // Return the first match
        }
        return null;
    }

    private void submitFormWithImo(String imoNumber) {
        String jsessionid = sessionService.getJsessionid();
        if (jsessionid == null) {
            throw new IllegalStateException("JSESSIONID is missing. Please log in first.");
        }

        String url = "https://www.equasis.org/EquasisWeb/restricted/ShipInfo?fs=HomePage&P_IMO=" + imoNumber;

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);

        HttpEntity<String> request = new HttpEntity<>(headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            parseAndWriteDetailedInfo(response.getBody());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseAndWriteDetailedInfo(String html) {
    	Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");
 
        try (PrintWriter writer = new PrintWriter(new FileWriter("company_info.csv"))) {
            writer.println("IMO number,Company name,Date of effect");
 
            for (Element row : rows) {
                Elements cells = row.select("td");
 
                String imoNumber = cells.size() > 0 ? cells.get(0).text().trim() : "";
                String companyName = cells.size() > 2 ? cells.get(2).text().trim() : "";
                String dateOfEffect = cells.size() > 4 ? cells.get(4).text().trim() : "";
 
                writer.printf("%s,%s,%s%n", imoNumber, companyName, dateOfEffect);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
