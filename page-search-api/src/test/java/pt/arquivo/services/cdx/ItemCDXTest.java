package pt.arquivo.services.cdx;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ItemCDXTest {

    private ItemCDX itemCDX1;
    private ItemCDX itemCDX2;

    @Before
    public void setUp() throws Exception {
        itemCDX1 = new ItemCDX("http://example.com", "201901010101203401", "DHAJKWDAK",
                "text/html", "200", "something", "80", "80", "TESTE");
        itemCDX2 = new ItemCDX("", "201901010101203400", "DHAJKWDA",
                "text/html", "200", "something", "80", "80", "TESTE");
    }

    @Test
    public void testToString() {
        assertThat(itemCDX1.toString()).isEqualTo("ItemCDX [url=http://example.com, timestamp=201901010101203401, digest=DHAJKWDAK, mime=text/html, statusCode=200, filename=something, length=80, offset=80]");
        assertThat(itemCDX1.hashCode()).isEqualTo(-1970849035);
    }

    @Test
    public void testEquals() {
        assertThat(itemCDX1.equals(itemCDX2)).isFalse();
    }

    @Test
    public void checkFields() {
        assertThat(itemCDX1.checkFields()).isTrue();
        assertThat(itemCDX2.checkFields()).isFalse();
    }

    private static ItemCDX fullItemCDX() {
        return new ItemCDX("http://example.com", "201901010101203401", "DHAJKWDAK",
                "text/html", "200", "something", "80", "80", "TESTE");
    }

    @Test
    public void checkFields_nullTimestamp_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setTimestamp(null);

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void checkFields_blankDigest_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setDigest("");

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void checkFields_blankMime_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setMime("");

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void checkFields_blankStatus_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setStatus("");

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void checkFields_blankFilename_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setFilename("");

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void checkFields_blankLength_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setLength("");

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void checkFields_blankOffset_returnsFalse() {
        ItemCDX itemCDX = fullItemCDX();
        itemCDX.setOffset("");

        assertThat(itemCDX.checkFields()).isFalse();
    }

    @Test
    public void equals_sameInstance_returnsTrue() {
        assertThat(itemCDX1.equals(itemCDX1)).isTrue();
    }

    @Test
    public void equals_notAnItemCDX_returnsFalse() {
        assertThat(itemCDX1.equals("not an ItemCDX")).isFalse();
    }

    @Test
    public void equals_sameDigestDifferentTimestamp_returnsFalse() {
        ItemCDX other = new ItemCDX("http://example.com", "201901010101203402", "DHAJKWDAK",
                "text/html", "200", "something", "80", "80", "TESTE");

        assertThat(itemCDX1.equals(other)).isFalse();
    }

    @Test
    public void equals_sameDigestAndTimestamp_returnsTrue() {
        ItemCDX other = new ItemCDX("http://example.com", "201901010101203401", "DHAJKWDAK",
                "text/html", "200", "something", "80", "80", "TESTE");

        assertThat(itemCDX1.equals(other)).isTrue();
    }

}