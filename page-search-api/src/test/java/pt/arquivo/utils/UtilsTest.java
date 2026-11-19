package pt.arquivo.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UtilsTest {

    @Test
    public void urlValidator() {
        String url1 = "https://example.com/path1/path2?parameter1=something&parameter2=something";
        String url2 = "example.com/path1/path2?paramter1=something&parameter2=something";
        String url3 = "http://example.com?url=http://arquivo.pt";
        String notUrl1 = "http://naturalmenteistonaoeumurl/path1";
        String notUrl2 = "term1 term2";

        assertThat(Utils.urlValidator(url1)).isEqualTo(true);
        assertThat(Utils.urlValidator(url2)).isEqualTo(true);
        assertThat(Utils.urlValidator(url3)).isEqualTo(true);
        assertThat(Utils.urlValidator(notUrl1)).isEqualTo(false);
        assertThat(Utils.urlValidator(notUrl2)).isEqualTo(false);
    }

    @Test
    public void metadataValidator(){
        String[] validMetadata = new String[] {"https://example.com", "201901301002010234"};
        String[] notValidMetadata = new String[] {"", "201901301002010234"};
        assertThat(Utils.metadataValidator(validMetadata)).isTrue();
        assertThat(Utils.metadataValidator(notValidMetadata)).isFalse();
    }
}