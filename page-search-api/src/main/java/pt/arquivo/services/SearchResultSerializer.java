package pt.arquivo.services;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

public class SearchResultSerializer extends JsonSerializer {

    private static final Logger LOG = LoggerFactory.getLogger(SearchResultSerializer.class);

    /**
     * Fields of the result classes that are only used internally, so they are never written, not even when asked for
     * in the fields parameter. The static fields, like the loggers, aren't written either.
     */
    private static final List<String> INTERNAL_FIELDS = Arrays.asList("fields", "solrClient", "timeAllowed", "hostKey");

    @Value("${searchpages.api.show.ids}")
    boolean showIds;

    @Override
    public void serialize(Object o, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        SearchResult searchResult = (SearchResult) o;
        jsonGenerator.writeStartObject();
        if (searchResult.getFields() != null) {
            for (Field field : searchResult.getClass().getDeclaredFields()) {
                if (isApiField(field) && serializeField(field.getName(), searchResult.getFields())) {
                    try {
                        field.setAccessible(true);
                        if (field.get(searchResult) != null)
                            jsonGenerator.writeObjectField(field.getName(), field.get(searchResult));
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
                                jsonGenerator.writeObjectField(field.getName(), field.get(searchResult));
                            }
                        } else {
                            jsonGenerator.writeObjectField(field.getName(), field.get(searchResult));
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
