package com.dartintel.api.api;

import com.dartintel.api.api.dto.PricingResponse;
import com.dartintel.api.payment.X402Paywall;
import com.dartintel.api.payment.X402Properties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Public, unpaid endpoints. Lives at {@code /v1} so the x402 paywall
 * interceptor sees the handlers (they simply have no {@link X402Paywall}
 * annotation, so the interceptor short-circuits).
 */
@RestController
@RequestMapping("/v1")
public class PublicController {

    private final X402Properties x402Properties;
    private final RequestMappingHandlerMapping handlerMapping;

    public PublicController(
            X402Properties x402Properties,
            // Spring Boot also registers a "controllerEndpointHandlerMapping" for actuator;
            // we only want the MVC-controller one here.
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        this.x402Properties = x402Properties;
        this.handlerMapping = handlerMapping;
    }

    @GetMapping("/pricing")
    public ResponseEntity<PricingResponse> pricing() {
        List<PricingResponse.PaidEndpoint> paid = handlerMapping.getHandlerMethods().entrySet()
                .stream()
                .filter(e -> e.getValue().hasMethodAnnotation(X402Paywall.class))
                .map(PublicController::toPaidEndpoint)
                .sorted(Comparator.comparing(PricingResponse.PaidEndpoint::path)
                        .thenComparing(PricingResponse.PaidEndpoint::method))
                .toList();

        return ResponseEntity.ok(new PricingResponse(
                x402Properties.network(),
                x402Properties.asset(),
                x402Properties.recipientAddress(),
                paid
        ));
    }

    private static PricingResponse.PaidEndpoint toPaidEndpoint(
            Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        HandlerMethod handler = entry.getValue();
        X402Paywall annotation = handler.getMethodAnnotation(X402Paywall.class);
        RequestMappingInfo info = entry.getKey();

        String path = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString()
                : info.getPatternValues().iterator().next();
        String method = info.getMethodsCondition().getMethods().isEmpty()
                ? "GET"
                : info.getMethodsCondition().getMethods().iterator().next().name();

        return new PricingResponse.PaidEndpoint(
                method,
                path,
                annotation.priceUsdc(),
                annotation.description()
        );
    }
}
