package dev.cgt.pixelplace.auth.oauth2;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** 인증 완료된 envelope의 명시적 JSON 필드만 복원. polymorphic·Java 객체 역직렬화 사용 금지 */
class AuthorizationRequestJson {
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    byte[] write(OAuth2AuthorizationRequest request, Instant expiresAt) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("authorizationUri", request.getAuthorizationUri());
        fields.put("grantType", request.getGrantType().getValue());
        fields.put("responseType", request.getResponseType().getValue());
        fields.put("clientId", request.getClientId());
        fields.put("redirectUri", request.getRedirectUri());
        fields.put("scopes", request.getScopes());
        fields.put("state", request.getState());
        fields.put("attributes", stringMap(request.getAttributes()));
        fields.put("additionalParameters", stringMap(request.getAdditionalParameters()));
        fields.put("authorizationRequestUri", request.getAuthorizationRequestUri());
        return json.writeValueAsBytes(Map.of("version", 1, "expiresAt", expiresAt.toString(), "request", fields));
    }

    OAuth2AuthorizationRequest read(byte[] plaintext, Instant now) {
        JsonNode envelope = json.readTree(plaintext);
        exactFields(envelope, Set.of("version", "expiresAt", "request"));
        require(envelope.path("version").isIntegralNumber() && envelope.path("version").intValue() == 1
                && envelope.path("version").canConvertToInt());
        // 브라우저 Max-Age와 무관하게 서버 Clock의 exact 만료 경계 적용
        require(now.isBefore(Instant.parse(string(envelope, "expiresAt"))));
        JsonNode request = envelope.path("request");
        exactFields(request, Set.of("authorizationUri", "grantType", "responseType", "clientId", "redirectUri",
                "scopes", "state", "attributes", "additionalParameters", "authorizationRequestUri"));
        require("authorization_code".equals(string(request, "grantType")) && "code".equals(string(request, "responseType")));
        JsonNode scopes = request.path("scopes");
        require(scopes.isArray());
        Set<String> scopeSet = new LinkedHashSet<>();
        for (JsonNode scope : scopes) {
            require(scope.isString() && !scope.stringValue().isBlank() && scopeSet.add(scope.stringValue()));
        }
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(string(request, "authorizationUri")).clientId(string(request, "clientId"))
                .redirectUri(string(request, "redirectUri")).scopes(scopeSet).state(string(request, "state"))
                .attributes(readStringMap(request.path("attributes")))
                .additionalParameters(readStringMap(request.path("additionalParameters")))
                .authorizationRequestUri(string(request, "authorizationRequestUri")).build();
    }

    private static Map<String, Object> stringMap(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((name, value) -> {
            // C-05의 registration/verifier/challenge를 포함한 문자열 확장값만 보존. 손실 변환 금지
            require(name != null && !name.isBlank() && value instanceof String);
            result.put(name, value);
        });
        return result;
    }

    private static Map<String, Object> readStringMap(JsonNode node) {
        require(node.isObject());
        var result = new LinkedHashMap<String, Object>();
        for (var entry : node.properties()) {
            require(!entry.getKey().isBlank() && entry.getValue().isString());
            result.put(entry.getKey(), entry.getValue().stringValue());
        }
        return result;
    }

    private static void exactFields(JsonNode node, Set<String> names) {
        require(node.isObject() && node.size() == names.size());
        for (String name : names) require(node.has(name));
    }

    private static String string(JsonNode node, String name) {
        JsonNode value = node.path(name);
        require(value.isString() && !value.stringValue().isBlank());
        return value.stringValue();
    }

    static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid authorization request");
    }
}
