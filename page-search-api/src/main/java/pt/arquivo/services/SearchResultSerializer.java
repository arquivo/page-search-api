package pt.arquivo.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

public class SearchResultSerializer extends ValueSerializer<SearchResult> {

    private static final Logger LOG = LoggerFactory.getLogger(SearchResultSerializer.class);

    /**
     * Fields of the result classes that are only used internally, so they are never written, not even when asked for
     * in the fields parameter. The static fields, like the loggers, aren't written either.
     */
    static final List<String> INTERNAL_FIELDS = Arrays.asList("fields", "solrClient", "timeAllowed", "hostKey");

    @Value("${searchpages.api.show.ids}")
    boolean showIds;

    @Override
    public void serialize(SearchResult searchResult, JsonGenerator jsonGenerator, SerializationContext context) {
        jsonGenerator.writeStartObject();
        if (searchResult.getFields() != null) {
            for (Field field : searchResult.getClass().getDeclaredFields()) {
                if (isApiField(field) && serializeField(field.getName(), searchResult.getFields())) {
                    try {
                        field.setAccessible(true);
                        if (field.get(searchResult) != null)
                            jsonGenerator.writePOJOProperty(field.getName(), field.get(searchResult));
                    } catch (IllegalAccessException e) {
                        LOG.error("Error trying to access field", e);
                    }
                }
            }
        } else {
            for (Field field : searchResult.getClass().getDeclaredFields()) {
                if (!isApiField(field)) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(searchResult);
                    if (value != null) {
                        if (field.getName().equals("id")) {
                            if (showIds) {
                                jsonGenerator.writePOJOProperty(field.getName(), field.get(searchResult));
                            }
                        } else {
                            jsonGenerator.writePOJOProperty(field.getName(), field.get(searchResult));
                        }
                    }
                } catch (IllegalAccessException e) {
                    LOG.error("Error trying to access field", e);
                }
            }
        }
        jsonGenerator.writeEndObject();
    }

    private static boolean isApiField(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                && !INTERNAL_FIELDS.contains(field.getName());
    }

    private boolean serializeField(String fieldName, String[] fields) {
        if (fields != null) {
            for (String field : fields) {
                if (fieldName.equalsIgnoreCase(field))
                    return true;
            }
            return false;
        } else {
            return true;
        }
    }

}
