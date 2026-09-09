package com.dartintel.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code resource} object of a 402 challenge / payment payload
 * (x402 v2 §5.1). Besides the identifying {@code url}, the Bazaar
 * discovery extension reads the optional provider-level
 * {@code serviceName}, {@code tags} and {@code iconUrl} from here to
 * render and rank the catalog listing — "no out-of-band admin step".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceInfo(
        String url,
        String description,
        String mimeType,
        String serviceName,
        List<String> tags,
        String iconUrl
) {

    public ResourceInfo(String url, String description, String mimeType) {
        this(url, description, mimeType, null, null, null);
    }

    /**
     * Fill in provider branding the client did not supply. Fields the
     * client set are kept (x402 v2 §5.2 — never overwrite what the
     * client sent); {@code url}, {@code description} and
     * {@code mimeType} are untouched.
     */
    public ResourceInfo withBrandingIfAbsent(String serviceName, List<String> tags, String iconUrl) {
        String name = this.serviceName != null ? this.serviceName : serviceName;
        List<String> tagList = this.tags != null ? this.tags : tags;
        String icon = this.iconUrl != null ? this.iconUrl : iconUrl;
        if (name == this.serviceName && tagList == this.tags && icon == this.iconUrl) {
            return this;
        }
        return new ResourceInfo(url, description, mimeType, name, tagList, icon);
    }
}
