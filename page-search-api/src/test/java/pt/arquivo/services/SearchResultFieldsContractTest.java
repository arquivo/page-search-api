package pt.arquivo.services;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Parameter;
import org.junit.Test;
import org.mockito.Mockito;
import org.objenesis.ObjenesisStd;
import org.springframework.web.bind.annotation.RequestParam;
import pt.arquivo.api.PageSearchController;
import pt.arquivo.services.nutchwax.SearchResultNutchWaxImpl;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against internal fields of the result classes leaking into the API replies. The serializer writes the
 * declared fields by reflection, so a field added to a result class is replied unless it's listed as internal: every
 * field has to be either documented as a value of the fields parameter or listed in
 * {@link SearchResultSerializer#INTERNAL_FIELDS}.
 */
public class SearchResultFieldsContractTest {

    private static final List<Class<? extends SearchResult>> RESULT_CLASSES = Arrays.asList(
            SearchResultSolrImpl.class, SearchResultNutchImpl.class, SearchResultNutchWaxImpl.class);

    @Test
    public void everyResultFieldIsDocumentedOrInternal() {
        List<String> documented = documentedFields();
        for (Class<? extends SearchResult> resultClass : RESULT_CLASSES) {
            for (Field field : instanceFields(resultClass)) {
                boolean isDocumented = documented.contains(field.getName());
                boolean isInternal = SearchResultSerializer.INTERNAL_FIELDS.contains(field.getName());
                assertThat(isDocumented ^ isInternal)
                        .as("%s.%s must be either documented in the fields parameter of PageSearchController "
                                        + "or listed in SearchResultSerializer.INTERNAL_FIELDS, not both",
                                field.getDeclaringClass().getSimpleName(), field.getName())
                        .isTrue();
            }
        }
    }

    @Test
    public void everyDocumentedFieldIsAResultField() {
        Set<String> resultFields = new LinkedHashSet<>();
        for (Class<? extends SearchResult> resultClass : RESULT_CLASSES) {
            for (Field field : instanceFields(resultClass)) {
                resultFields.add(field.getName());
            }
        }
        assertThat(resultFields).containsAll(documentedFields());
    }

    @Test
    public void everyInternalFieldIsAResultField() {
        // A stale name here would hide that the field it was meant to exclude got renamed, and is now replied
        Set<String> resultFields = new LinkedHashSet<>();
        for (Class<? extends SearchResult> resultClass : RESULT_CLASSES) {
            for (Field field : instanceFields(resultClass)) {
                resultFields.add(field.getName());
            }
        }
        assertThat(resultFields).containsAll(SearchResultSerializer.INTERNAL_FIELDS);
    }

    @Test
    public void defaultModeRepliesOnlyDocumentedFields() throws Exception {
        for (Class<? extends SearchResult> resultClass : RESULT_CLASSES) {
            SearchResult result = populatedResult(resultClass, null);

            assertThat(keys(serialize(result, true)))
                    .as("fields replied for %s", resultClass.getSimpleName())
                    .isSubsetOf(documentedFields());
        }
    }

    @Test
    public void defaultModeRepliesEveryDocumentedSolrField() throws Exception {
        assertThat(keys(serialize(populatedResult(SearchResultSolrImpl.class, null), true)))
                .containsExactlyInAnyOrderElementsOf(documentedFields());
    }

    @Test
    public void whitelistModeRepliesOnlyDocumentedFieldsEvenIfAllAreRequested() throws Exception {
        for (Class<? extends SearchResult> resultClass : RESULT_CLASSES) {
            // Every declared name, static ones like LOG included, in both cases since the match ignores case
            List<String> requested = new ArrayList<>();
            for (Class<?> c = resultClass; c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    requested.add(field.getName());
                    requested.add(field.getName().toUpperCase());
                    requested.add(field.getName().toLowerCase());
                }
            }
            SearchResult result = populatedResult(resultClass, requested.toArray(new String[0]));

            assertThat(keys(serialize(result, false)))
                    .as("fields replied for %s", resultClass.getSimpleName())
                    .isSubsetOf(documentedFields());
        }
    }

    /**
     * The allowed values of the fields parameter in the OpenAPI docs, except spellcheck, which isn't a result field.
     */
    private static List<String> documentedFields() {
        for (Method method : PageSearchController.class.getDeclaredMethods()) {
            if (!method.getName().equals("pageSearch")) {
                continue;
            }
            for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                if (requestParam != null && "fields".equals(requestParam.value())) {
                    List<String> documented = new ArrayList<>(Arrays.asList(
                            parameter.getAnnotation(Parameter.class).array().schema().allowableValues()));
                    documented.remove(SearchQuery.SPELLCHECK_FIELD);
                    return documented;
                }
            }
        }
        throw new AssertionError("fields parameter of PageSearchController.pageSearch not found");
    }

    private static List<Field> instanceFields(Class<?> resultClass) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = resultClass; c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /**
     * A result with every instance field set, so any field the serializer doesn't filter out shows in the reply.
     * The requested fields, null for the default mode, go in the fields field.
     */
    private static SearchResult populatedResult(Class<? extends SearchResult> resultClass, String[] requested)
            throws Exception {
        SearchResult result = resultClass.getDeclaredConstructor().newInstance();
        for (Field field : instanceFields(resultClass)) {
            field.setAccessible(true);
            field.set(result, field.getName().equals("fields") ? requested : sampleValue(field));
        }
        return result;
    }

    private static Object sampleValue(Field field) {
        Class<?> type = field.getType();
        if (type == String.class) {
            return field.getName();
        } else if (type == Long.class || type == long.class) {
            return 1L;
        } else if (type == Integer.class || type == int.class) {
            return 1;
        }
        // Mockito can't mock final classes, like nutch's HitDetails, so those are instantiated skipping the constructor
        return Modifier.isFinal(type.getModifiers()) ? new ObjenesisStd().newInstance(type) : Mockito.mock(type);
    }

    private static JsonNode serialize(SearchResult result, boolean showIds) throws IOException {
        SearchResultSerializer serializer = new SearchResultSerializer();
        serializer.showIds = showIds;
        ObjectMapper mapper = new ObjectMapper();
        StringWriter writer = new StringWriter();
        JsonGenerator generator = mapper.getFactory().createGenerator(writer);
        serializer.serialize(result, generator, null);
        generator.close();
        return mapper.readTree(writer.toString());
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            keys.add(names.next());
        }
        return keys;
    }
}
