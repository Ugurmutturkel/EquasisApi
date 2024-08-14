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

import java.util.*;
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
            return ResponseEntity.ok("{\"JSESSIONID\":\"" + jsessionid + "\"}");
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                 .body("{\"error\":\"JSESSIONID not found in the response.\"}");
        }
    }

    @PostMapping("/searchimo")
    public ResponseEntity<Map<String, Object>> performSearch(@RequestBody SearchRequest searchRequest) {
        String jsessionid = sessionService.getJsessionid();
        if (jsessionid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                 .body(Collections.singletonMap("error", "JSESSIONID is missing. Please log in first."));
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

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            // Parse HTML to JSON
            Map<String, Object> htmlJson = parseHtmlToJson(response.getBody());
            
            // Extract additional IMO details directly
            Map<String, Object> imoDetails = extractIMODetails(response.getBody(), jsessionid);
            
            // Combine both JSON responses
            Map<String, Object> combinedJson = new HashMap<>();
            combinedJson.put("searchResults", htmlJson);
            combinedJson.put("imoDetails", imoDetails);

            return ResponseEntity.ok(combinedJson);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(Collections.singletonMap("error", "Failed to perform search: " + e.getMessage()));
        }
    }

    @PostMapping("/searchcompany")
    public ResponseEntity<Map<String, Object>> performSearchComp(@RequestBody SearchRequest searchRequest) {
        String jsessionid = sessionService.getJsessionid();
        if (jsessionid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                 .body(Collections.singletonMap("error", "JSESSIONID is missing. Please log in first."));
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
            return ResponseEntity.ok(parseCompanyDetailsToJson(response.getBody()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(Collections.singletonMap("error", "Failed to perform search: " + e.getMessage()));
        }
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

    private Map<String, Object> parseHtmlToJson(String html) {
        Set<String> existingRecords = new HashSet<>();
        List<Map<String, String>> shipList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        for (Element row : rows) {
            String imoNumber = row.select("th").text().trim();
            Elements dataCells = row.select("td");

            String nameOfShip = dataCells.size() > 0 ? dataCells.get(0).text().trim() : "";
            String grossTonnage = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
            String typeOfShip = dataCells.size() > 2 ? dataCells.get(2).text().trim() : "";
            String yearOfBuild = dataCells.size() > 3 ? dataCells.get(3).text().trim() : "";
            String flag = dataCells.size() > 4 ? dataCells.get(4).text().trim() : "";

            if (!imoNumber.isEmpty()) {
                Map<String, String> shipData = new HashMap<>();
                shipData.put("IMO Number", imoNumber);
                shipData.put("Name of Ship", nameOfShip);
                shipData.put("Gross Tonnage", grossTonnage);
                shipData.put("Type of Ship", typeOfShip);
                shipData.put("Year of Build", yearOfBuild);
                shipData.put("Flag", flag);

                String record = String.format("%s,%s,%s,%s,%s,%s", imoNumber, nameOfShip, grossTonnage, typeOfShip, yearOfBuild, flag);
                if (!existingRecords.contains(record)) {
                    shipList.add(shipData);
                    existingRecords.add(record);
                }
            }
        }

        return Collections.singletonMap("ships", shipList);
    }

    private Map<String, Object> parseCompanyDetailsToJson(String html) {
        Set<String> existingRecords = new HashSet<>();
        List<Map<String, String>> companyList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        for (Element row : rows) {
            Elements headerCells = row.select("th");
            Elements dataCells = row.select("td");

            String companyNumber = headerCells.size() > 0 ? headerCells.get(0).text().trim() : "";
            String companyName = dataCells.size() > 0 ? dataCells.get(0).text().trim() : "";
            String address = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
            address = address.replace(",", ";");

            if (!companyNumber.isEmpty() && !companyName.isEmpty()) {
                Map<String, String> companyData = new HashMap<>();
                companyData.put("Company Number", companyNumber);
                companyData.put("Company Name", companyName);
                companyData.put("Address", address);

                String record = String.format("%s,%s,%s", companyNumber, companyName, address);
                if (!existingRecords.contains(record)) {
                    companyList.add(companyData);
                    existingRecords.add(record);
                }
            }
        }

        return Collections.singletonMap("companies", companyList);
    }
    private Map<String, Object> extractIMODetails(String html, String jsessionid) {
        List<Map<String, String>> imoDetailsList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        for (Element row : rows) {
            String imoNumber = row.select("th").text().trim();
            Elements dataCells = row.select("td");

            String nameOfCompany = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
            String dateOfEffect = dataCells.size() > 4 ? dataCells.get(4).text().trim() : "";

            // Extract the IMO number from the onclick attribute
            Element detailsLink = row.select("td:last-child a[style='cursor: pointer']").first();
            if (detailsLink != null) {
                String onclickValue = detailsLink.attr("onclick");
                String extractedImoNumber = extractIMONumberFromOnclick(onclickValue);

                // Simulate the form submission to get detailed info
                Map<String, String> detailedInfo = getDetailedIMOInfo(extractedImoNumber, jsessionid);
                

                if (!imoNumber.isEmpty()) {
                    Map<String, String> imoDetail = new HashMap<>();
                    imoDetail.put("IMO Number", imoNumber);
                    imoDetail.put("Name of Company", nameOfCompany);
                    imoDetail.put("Date of Effect", dateOfEffect);
                    if (detailedInfo != null) {
                        imoDetail.putAll(detailedInfo);
                    }

                    imoDetailsList.add(imoDetail);
                }
            }
        }


        return Collections.singletonMap("IMO Details", imoDetailsList);
    }

    private String extractIMONumberFromOnclick(String onclickValue) {
        Pattern pattern = Pattern.compile("document\\.formShip\\.P_IMO\\.value='(\\d+)';");
        Matcher matcher = pattern.matcher(onclickValue);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Map<String, String> getDetailedIMOInfo(String imoNumber, String jsessionid) {
        Map<String, String> detailedInfo = new HashMap<>();

        String detailsUrl = "https://www.equasis.org/EquasisWeb/restricted/Details";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);

            String requestBody = "P_IMO=" + imoNumber;

            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(detailsUrl, HttpMethod.POST, request, String.class);

            Document doc = Jsoup.parse(response.getBody());
            // Assuming specific elements or patterns to extract detailed information
            Element detailedInfoElement = doc.selectFirst(".detailed-info-selector");
            if (detailedInfoElement != null) {
                detailedInfo.put("Detailed Info", detailedInfoElement.text().trim());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return detailedInfo;
    }

}
