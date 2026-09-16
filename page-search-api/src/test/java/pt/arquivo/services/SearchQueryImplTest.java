package pt.arquivo.services;

import org.junit.Before;
import org.junit.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SearchQueryImplTest {

    private SearchQueryImpl searchQuery;

    @Before
    public void setupSearchQuery() {
        searchQuery = new SearchQueryImpl("sapo ya");
        searchQuery.setOffset(0);
        searchQuery.setMaxItems(10);
        searchQuery.setDedupValue(2);
        searchQuery.setFrom("20190101010101");
        searchQuery.setTo("202001010101");
        searchQuery.setType(new String[]{"text/html"});
        searchQuery.setSite(new String[]{"http://sapo.pt"});
        searchQuery.setCollection(new String[]{"FAWP"});
        searchQuery.setFields(new String[]{"title", "collection"});
        searchQuery.setPrettyPrint(false);
    }

    @Test
    public void getQueryTerms() {
        assertEquals(searchQuery.getQueryTerms(), "sapo ya");
    }

    @Test
    public void setQueryTerms() {
        searchQuery.setQueryTerms("sapo");
        assertEquals(searchQuery.getQueryTerms(), "sapo");
    }

    @Test
    public void getStart() {
        assertEquals(searchQuery.getOffset(), 0);
    }

    @Test
    public void setStart() {
        searchQuery.setOffset(10);
        assertEquals(10, searchQuery.getOffset());
    }

    @Test
    public void getLimit() {
        assertEquals(10, searchQuery.getMaxItems());
    }

    @Test
    public void setLimit() {
        searchQuery.setMaxItems(50);
        assertEquals(50, searchQuery.getMaxItems());

        searchQuery.setMaxItems(-100);
        assertEquals(0, searchQuery.getMaxItems());
    }

    @Test
    public void getDedupValue() {
        assertEquals(2, searchQuery.getDedupValue());
    }

    @Test
    public void setDedupValue() {
        searchQuery.setDedupValue(4);
        assertEquals(4, searchQuery.getDedupValue());
    }

    @Test
    public void getFrom() {
        assertEquals("20190101010101", searchQuery.getFrom());
    }

    @Test
    public void setFrom() {
        searchQuery.setFrom("201901010101010101");
        assertEquals("20190101010101", searchQuery.getFrom());
    }

    @Test
    public void getTo() {
        assertEquals("20200101010100", searchQuery.getTo());
    }

    @Test
    public void setTo() {
        searchQuery.setTo("2020010101010000");
        assertEquals("20200101010100", searchQuery.getTo());

        // should strip out extra characters
        searchQuery.setTo("202001010101010000000");
        assertEquals("20200101010101", searchQuery.getTo());
    }

    @Test
    public void getType() {
        assertEquals("text/html", searchQuery.getType()[0]);
    }

    @Test
    public void setType() {
        searchQuery.setType(new String[]{"application/pdf"});
        assertEquals(("application/pdf"), searchQuery.getType()[0]);
        assertTrue(searchQuery.isSearchByType());
    }

    @Test
    public void getSite() {
        assertEquals("http://sapo.pt", searchQuery.getSite()[0]);
    }

    @Test
    public void setSite() {
        searchQuery.setSite(new String[]{"http://arquivo.pt"});
        assertEquals("http://arquivo.pt", searchQuery.getSite()[0]);
        assertTrue(searchQuery.isSearchBySite());
    }

    @Test
    public void getCollection() {
        assertEquals("FAWP", searchQuery.getCollection()[0]);
    }

    @Test
    public void setCollection() {
        searchQuery.setCollection(new String[]{"CUSTOM"});
        assertEquals("CUSTOM", searchQuery.getCollection()[0]);
        assertTrue(searchQuery.isSearchByCollection());
    }

    @Test
    public void getPrettyPrint() {
        assertEquals(false, searchQuery.getPrettyPrint());
    }

    @Test
    public void setPrettyPrint() {
        searchQuery.setPrettyPrint(false);
        assertEquals(false, searchQuery.getPrettyPrint());
    }

    @Test
    public void getFields() {
        String[] fields = searchQuery.getFields();
        assertEquals("title", fields[0]);
        assertEquals("collection", fields[1]);
    }

    @Test
    public void setFields() {
        searchQuery.setFields(new String[]{"encoding", "type"});
    }

    @Test
    public void setLanguage() {
        searchQuery.setLanguage(" PT ");
        assertEquals("pt", searchQuery.getLanguage());
        assertTrue(searchQuery.isSearchByLanguage());

        searchQuery.setLanguage(null);
        assertNull(searchQuery.getLanguage());
        assertFalse(searchQuery.isSearchByLanguage());
    }

    @Test
    public void getMinLanguageConfidence() {
        // no confidence filtering when the query doesn't filter by language either
        assertNull(searchQuery.getMinLanguageConfidence());

        // filtering by language only keeps the confident detections by default
        searchQuery.setLanguage("pt");
        assertEquals("HIGH", searchQuery.getMinLanguageConfidence());
    }

    @Test
    public void setMinLanguageConfidence() {
        searchQuery.setMinLanguageConfidence("medium");
        assertEquals("MEDIUM", searchQuery.getMinLanguageConfidence());

        searchQuery.setLanguage("pt");
        assertEquals("MEDIUM", searchQuery.getMinLanguageConfidence());

        searchQuery.setMinLanguageConfidence("HIGH");
        assertEquals("HIGH", searchQuery.getMinLanguageConfidence());

        searchQuery.setMinLanguageConfidence("low");
        assertEquals("LOW", searchQuery.getMinLanguageConfidence());
    }

    @Test
    public void setInvalidMinLanguageConfidence() {
        // NONE is how the least confident tier is indexed, the API asks for it as LOW
        assertThrows(IllegalArgumentException.class, () -> searchQuery.setMinLanguageConfidence("NONE"));
        assertThrows(IllegalArgumentException.class, () -> searchQuery.setMinLanguageConfidence("VERY HIGH"));
        assertNull(searchQuery.getMinLanguageConfidence());
    }

    @Test
    public void testToString() {
        assertTrue(searchQuery.toString().length() > 0);
        searchQuery.setSite(null);
        searchQuery.setCollection(null);
        assertTrue(searchQuery.toString().length() > 0);
    }

    @Test
    public void getQuotedQueryTerms_nullQuery_returnsNull() {
        SearchQueryImpl freshQuery = new SearchQueryImpl(null);
        assertNull(freshQuery.getQuotedQueryTerms());
    }

    @Test
    public void getQuotedQueryTerms_alreadyDoubleQuoted_isNotQuotedAgain() {
        SearchQueryImpl freshQuery = new SearchQueryImpl("\"sapo\"");
        assertEquals("\"sapo\"", freshQuery.getQuotedQueryTerms());
    }

    @Test
    public void getQuotedQueryTerms_alreadySingleQuoted_isNotQuotedAgain() {
        SearchQueryImpl freshQuery = new SearchQueryImpl("'sapo'");
        assertEquals("'sapo'", freshQuery.getQuotedQueryTerms());
    }

    @Test
    public void getQuotedQueryTerms_unquoted_isQuoted() {
        assertEquals("\"sapo ya\"", searchQuery.getQuotedQueryTerms());
    }

    @Test
    public void setMaxItems_overCap_isClampedToMax() {
        searchQuery.setMaxItems(10000);
        assertEquals(500, searchQuery.getMaxItems());
    }

    @Test
    public void isTimeBoundedQuery_fromAndToUnset_returnsFalse() {
        SearchQueryImpl freshQuery = new SearchQueryImpl("sapo");
        assertFalse(freshQuery.isTimeBoundedQuery());
    }

    @Test
    public void isTimeBoundedQuery_onlyFromSet_returnsTrue() {
        SearchQueryImpl freshQuery = new SearchQueryImpl("sapo");
        freshQuery.setFrom("20190101");
        assertTrue(freshQuery.isTimeBoundedQuery());
    }

    @Test
    public void isTimeBoundedQuery_onlyToSet_returnsTrue() {
        SearchQueryImpl freshQuery = new SearchQueryImpl("sapo");
        freshQuery.setTo("20190101");
        assertTrue(freshQuery.isTimeBoundedQuery());
    }

    @Test
    public void isSearchByTitle_titleSearchUnset_returnsFalse() {
        assertFalse(searchQuery.isSearchByTitle());
    }

    @Test
    public void isSearchByTitle_titleSearchSet_returnsTrue() {
        searchQuery.setTitleSearch("arquivo");
        assertTrue(searchQuery.isSearchByTitle());
    }

    @Test
    public void isSpellcheck_fieldsNull_returnsFalse() {
        searchQuery.setFields(null);
        assertFalse(searchQuery.isSpellcheck());
    }

    @Test
    public void isSpellcheck_fieldsWithoutSpellcheck_returnsFalse() {
        searchQuery.setFields(new String[]{"title"});
        assertFalse(searchQuery.isSpellcheck());
    }

    @Test
    public void isSpellcheck_fieldsWithSpellcheckCaseInsensitive_returnsTrue() {
        searchQuery.setFields(new String[]{"title", "SpellCheck"});
        assertTrue(searchQuery.isSpellcheck());
    }

    @Test
    public void setYearBalance_belowMin_isClampedToMin() {
        searchQuery.setYearBalance(-0.5);
        assertEquals(SearchQueryImpl.MIN_YEAR_BALANCE, searchQuery.getYearBalance(), 0.0001);
    }

    @Test
    public void setYearBalance_aboveMax_isClampedToMax() {
        searchQuery.setYearBalance(1.5);
        assertEquals(SearchQueryImpl.MAX_YEAR_BALANCE, searchQuery.getYearBalance(), 0.0001);
    }
}
