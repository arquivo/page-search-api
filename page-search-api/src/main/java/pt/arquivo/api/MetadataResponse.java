package pt.arquivo.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResultSolrImpl;

import java.util.ArrayList;

@Schema
public class MetadataResponse implements ApiResponse {
   @Schema(description = "Name of the service, e.g. Arquivo.pt - the Portuguese web-archive.")
   private String serviceName;

   @Schema(description = "Base URL of the service, which the links of each result start with.")
   private String linkToService;

   // Typed as the SearchResult interface, so the schema is taken from the class whose fields are serialized
   @ArraySchema(arraySchema = @Schema(description = "The requested capture: empty when it isn't archived, null when id isn't a valid <URL>/<timestamp>."),
           schema = @Schema(implementation = SearchResultSolrImpl.class))
   @JsonProperty("response_items")
   private ArrayList<SearchResult> responseItems;

   public String getServiceName() {
      return serviceName;
   }

   public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
   }

   public String getLinkToService() {
      return linkToService;
   }

   public void setLinkToService(String linkToService) {
      this.linkToService = linkToService;
   }

   public ArrayList<SearchResult> getResponseItems() {
      return responseItems;
   }

   public void setResponseItems(ArrayList<SearchResult> responseItems) {
      this.responseItems = responseItems;
   }
}
