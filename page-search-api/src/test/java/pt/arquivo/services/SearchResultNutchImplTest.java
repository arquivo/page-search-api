package pt.arquivo.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

public class SearchResultNutchImplTest {

    @Test
    public void testCustomSerialization() throws JsonProcessingException {
        SearchResultNutchImpl searchResult = new SearchResultNutchImpl();
        searchResult.setStatusCode(0);
        searchResult.setTitle("teste");

        HashMap<String, Object> hashMap = toJson(searchResult);

        // gson is casting to double
        assertThat(hashMap.get("contentLength")).isEqualTo(0.0);
        assertThat(hashMap.get("statusCode")).isEqualTo(0.0);

        String[] fields = {"title"};
        searchResult.setFields(fields);
        hashMap = toJson(searchResult);

        assertThat((hashMap.get("title"))).isNotNull();
        assertThat((hashMap.get("contentLength"))).isNull();
        assertThat(hashMap.get("statusCode")).isNull();
    }

    private HashMap<String, Object> toJson(SearchResultNutchImpl searchResult) throws JsonProcessingException {
        String json = new ObjectMapper().writeValueAsString(searchResult);
        Gson gson = new Gson();
        HashMap<String, Object> hashMap = gson.fromJson(json, HashMap.class);
        return hashMap;
    }

    @Test
    public void getSearchResultId() {
        SearchResultNutchImpl searchResult = new SearchResultNutchImpl();
        searchResult.setTimeStamp("2019");
        searchResult.setOriginalURL("http://example.com");
        assertThat(searchResult.getSearchResultId()).isEqualTo("2019/http://example.com");
    }
}