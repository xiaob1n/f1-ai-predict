package com.lbz.f1aipredict.it.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItEnvironmentIT {

    @Test
    void acceptsOnlyLocalUrlForTheAppleContainerDatabase() {
        assertTrue(ItEnvironment.isLoopbackDedicatedUrl(
                "jdbc:mysql://127.0.0.1:3306/f1_ai_predict?connectionTimeZone=UTC"));
        assertFalse(ItEnvironment.isLoopbackDedicatedUrl("jdbc:mysql://example.com:3306/f1_ai_predict"));
        assertFalse(ItEnvironment.isLoopbackDedicatedUrl("jdbc:mysql://127.0.0.1:0/f1_ai_predict"));
        assertFalse(ItEnvironment.isLoopbackDedicatedUrl("jdbc:mysql://127.0.0.1:3306/other"));
    }

    @Test
    void acceptsOnlyMatchingRandomRemoteSchemaAndMarker() {
        String schema = "f1_it_0123456789abcdef";
        String url = "jdbc:mysql://127.0.0.1:34567/" + schema
                + "?connectionTimeZone=UTC&characterEncoding=UTF-8&sslMode=DISABLED&allowPublicKeyRetrieval=true";
        assertEquals(schema, ItEnvironment.validateRemote(url, schema, schema));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url.replace("127.0.0.1", "mysql.example.com"), schema, schema));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url, schema, "f1_it_fedcba9876543210"));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url, "f1_it_fedcba9876543210", schema));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url.replace(schema, "f1_ai_predict"), "f1_ai_predict", "f1_ai_predict"));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url.replace(":34567/", ":0/"), schema, schema));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url.replace(":34567/", ":65536/"), schema, schema));
        assertThrows(IllegalStateException.class, () -> ItEnvironment.validateRemote(
                url + "&allowMultiQueries=true", schema, schema));
    }
}
