package com.linearlite.server.dto;

public class ImageUploadResponse {

    private String url;
    private String key;

    public ImageUploadResponse() {
    }

    public ImageUploadResponse(String url, String key) {
        this.url = url;
        this.key = key;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
