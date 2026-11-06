package app.folyo.provider;

import tools.jackson.databind.JsonNode;

/** Saglayici yanitlarinda eksik alanlar null olarak okunur. */
final class Json {

    private Json() {
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    static long number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? 0 : value.asLong(0);
    }
}
