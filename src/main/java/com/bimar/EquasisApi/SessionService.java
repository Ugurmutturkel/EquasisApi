package com.bimar.EquasisApi;

import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private String jsessionid;

    public String getJsessionid() {
        return jsessionid;
    }

    public void setJsessionid(String jsessionid) {
        this.jsessionid = jsessionid;
    }

    public void clearSession() {
        this.jsessionid = null;
    }
}
