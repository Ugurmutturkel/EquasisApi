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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
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
           
            parseHtmlAndWriteToCsv(response.getBody());
            simulateClickAndGetDetails(response.getBody());  
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
            parseCompanyDetailsAndWriteToCsv(response.getBody());
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
        Set<String> existingRecords = loadExistingRecords("Equasis_ships.csv");
        Set<String> existingNames = new HashSet<>();
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        try (PrintWriter writer = new PrintWriter(new FileWriter("Equasis_ships.csv", true))) {
            File csvFile = new File("Equasis_ships.csv");
            if (csvFile.length() == 0) {
                writer.println("IMO Number,Name of Ship,Gross Tonnage,Type of Ship,Year of Build,Flag");
            }

            boolean skipRow = false; 

            for (Element row : rows) {
                if (skipRow) {
                    skipRow = false; 
                    continue; 
                }
                String imoNumber = row.select("th").text().trim();
                Elements dataCells = row.select("td");

                String nameOfShip = dataCells.size() > 0 ? dataCells.get(0).text().trim() : "";
                String grossTonnage = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
                String typeOfShip = dataCells.size() > 2 ? dataCells.get(2).text().trim() : "";
                String yearOfBuild = dataCells.size() > 3 ? dataCells.get(3).text().trim() : "";
                String flag = dataCells.size() > 4 ? dataCells.get(4).text().trim() : "";

                String record = String.format("%s,%s,%s,%s,%s,%s", imoNumber, nameOfShip, grossTonnage, typeOfShip, yearOfBuild, flag);
                String uniqueKey = imoNumber + nameOfShip;
                if (!existingRecords.contains(record) && !existingNames.contains(uniqueKey)) {
                    writer.println(record);
                    existingRecords.add(record);
                    existingNames.add(uniqueKey);
                }

                skipRow = true; 
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseCompanyDetailsAndWriteToCsv(String html) {
        Set<String> existingRecords = loadExistingRecords("Equasis_companySearch.csv"); 
        Set<String> existingNames = new HashSet<>();
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        try (PrintWriter writer = new PrintWriter(new FileWriter("Equasis_companySearch.csv", true))) {
            File csvFile = new File("Equasis_companySearch.csv");
            if (csvFile.length() == 0) {
                writer.println("Company Number,Company Name,Address");
            }

            boolean skipRow = false; 

            for (Element row : rows) {
                if (skipRow) {
                    skipRow = false; 
                    continue; 
                }

                Elements headerCells = row.select("th");
                Elements dataCells = row.select("td");

                String record;
                String companyName;
                if (!headerCells.isEmpty()) {
                    String companyNumber = headerCells.size() > 0 ? headerCells.get(0).text().trim() : "";
                    companyName = dataCells.size() > 0 ? dataCells.get(0).text().trim() : "";
                    String address = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
                    address = address.replace(",", ";"); 

                    record = String.format("%s,%s,%s", companyNumber, companyName, address);
                } else if (!dataCells.isEmpty()) {
                    String companyNumber = dataCells.size() > 0 ? dataCells.get(0).text().trim() : "";
                    companyName = dataCells.size() > 1 ? dataCells.get(1).text().trim() : "";
                    String address = dataCells.size() > 2 ? dataCells.get(2).text().trim() : "";
                    address = address.replace(",", ";"); 

                    record = String.format("%s,%s,%s", companyNumber, companyName, address);
                } else {
                    continue; 
                }
                String uniqueKey = companyName;
                if (!existingRecords.contains(record) && !existingNames.contains(uniqueKey)) {
                    writer.println(record);
                    existingRecords.add(record);
                    existingNames.add(uniqueKey);
                }

                skipRow = true; 
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void parseIMOInfoAndWriteToCsv(String html) {
        Set<String> existingRecords = loadExistingRecords("Equasis_IMOinfo.csv");
        Set<String> existingNames = new HashSet<>();
        Document doc = Jsoup.parse(html);
        Elements rows = doc.select("table.table-striped tbody tr");

        try (PrintWriter writer = new PrintWriter(new FileWriter("Equasis_IMOinfo.csv", true))) {
            File csvFile = new File("Equasis_IMOinfo.csv");
            if (csvFile.length() == 0) {
                writer.println("IMO Number,Company Name,Date of Effect");
            }

            for (Element row : rows) {
                Elements cells = row.select("td");
                String imoNumber = cells.size() > 0 ? cells.get(0).text().trim() : "";
                if (imoNumber.isEmpty() || !imoNumber.matches("\\d{7}")) {
                    continue;
                }

                String companyName = cells.size() > 2 ? cells.get(2).text().trim() : "";
                String dateOfEffect = cells.size() > 4 ? cells.get(4).text().trim() : "";

                String record = String.format("%s,%s,%s", imoNumber, companyName, dateOfEffect);

                if (!existingRecords.contains(record) && !existingNames.contains(companyName)) {
                    writer.println(record);
                    existingRecords.add(record);
                    existingNames.add(companyName);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private Set<String> loadExistingRecords(String fileName) {
        Set<String> records = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                records.add(line.trim());
            }
        } catch (IOException e) {
            if (!(e instanceof FileNotFoundException)) {
                e.printStackTrace();
            }
        }
        return records;
    }


    private void simulateClickAndGetDetails(String html) {
        Document doc = Jsoup.parse(html);
        Elements links = doc.select("a[onclick]");

        for (Element link : links) {
            String onclick = link.attr("onclick");
            String imoNumber = extractImoFromOnclick(onclick);
            if (imoNumber != null) {
                submitFormWithImo(imoNumber);
            }
        }
    }

    private String extractImoFromOnclick(String onclick) {
        Pattern pattern = Pattern.compile("\\d{7}"); 
        Matcher matcher = pattern.matcher(onclick);

        if (matcher.find()) {
            return matcher.group(0); 
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
            parseIMOInfoAndWriteToCsv(response.getBody());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
