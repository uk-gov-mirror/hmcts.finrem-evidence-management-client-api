package uk.gov.hmcts.reform.finrem.emclient;

import io.restassured.RestAssured;
import io.restassured.filter.log.ErrorLoggingFilter;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.response.Response;
import net.serenitybdd.junit.runners.SerenityParameterizedRunner;
import net.serenitybdd.junit.spring.integration.SpringIntegrationMethodRule;
import net.serenitybdd.rest.SerenityRest;
import net.thucydides.junit.annotations.TestData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.cloud.netflix.ribbon.RibbonAutoConfiguration;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.ribbon.FeignRibbonClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.serenitybdd.rest.SerenityRest.given;
import static org.junit.Assert.assertEquals;

@Lazy
@RunWith(SerenityParameterizedRunner.class)
@ComponentScan(basePackages = {"uk.gov.hmcts.reform.finrem.emclient", "uk.gov.hmcts.auth.provider.service"})
@ImportAutoConfiguration({RibbonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
        FeignRibbonClientAutoConfiguration.class, FeignAutoConfiguration.class})
@ContextConfiguration(classes = {ServiceContextConfiguration.class})
@PropertySource("classpath:application.properties")
@PropertySource("classpath:application-${env}.properties")
public class EmClientFileUploadTest {

    @Rule
    public SpringIntegrationMethodRule springMethodIntegration = new SpringIntegrationMethodRule();

    @Value("${evidence.management.client.api.baseUrl}")
    private String evidenceManagementClientApiBaseUrl;

    @Value("${evidence.management.client.api.endpoint.uploadwiths2stoken}")
    private String emClientApiUploadEndpoint;

    @Value("${document.management.store.baseUrl}")
    private String dmStoreBaseUrl;

    @Autowired
    private IdamUtils idamTestSupportUtil;

    @Autowired
    private AuthTokenGenerator authTokenGenerator;

    private final String name;
    private final String fileType;

    private static final String[] fileName = {"PNGFile.png", "BMPFile.bmp", "PDFFile.pdf", "TIFFile.TIF", "JPEGFile.jpg",
        "PNGFile.png", "BMPFile.bmp", "PDFFile.pdf", "TIFFile.TIF", "JPEGFile.jpg"};

    private static final String[] fileContentType = {"image/png", "image/bmp", "application/pdf", "image/tiff", "image/jpeg",
        "image/png", "image/bmp", "application/pdf", "image/tiff", "image/jpeg"};

    @TestData
    public static Collection<Object[]> testData() {
        return IntStream.range(0, fileName.length)
                .mapToObj(i -> new String[]{fileName[i], fileContentType[i]})
                .collect(Collectors.toList());
    }

    public EmClientFileUploadTest(String filename, String fileContentType) {
        this.name = filename;
        this.fileType = fileContentType;
    }

    @Test
    public void uploadFile() {
        uploadFileToEmStore(this.name, this.fileType);
    }

    @SuppressWarnings("unchecked")
    private void uploadFileToEmStore(String fileToUpload, String fileContentType) {
        File file = new File("src/integrationTest/resources/FileTypes/" + fileToUpload);
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter(), new ErrorLoggingFilter());
        Response response = SerenityRest.given()
                .headers(getAuthenticationTokenHeader())
                .multiPart("file", file, fileContentType)
                .post(evidenceManagementClientApiBaseUrl.concat("/upload"))
                .andReturn();

        System.out.println("Response Body---->" + response.prettyPrint());
        assertEquals(HttpStatus.OK.value(), response.statusCode());
        String fileUrl = ((List<String>) response.getBody().path("fileUrl")).get(0);

        assertEmGetFileResponse(fileToUpload, fileContentType, fileRetrieveUrl(fileUrl));
    }

    private String fileRetrieveUrl(String url) {
        return dmStoreBaseUrl + "/documents/" + url.substring(url.lastIndexOf('/') + 1);
    }

    private void assertEmGetFileResponse(String fileToUpload, String fileContentType, String fileUrl) {
        Response responseFromEvidenceManagement = readDataFromEvidenceManagement(fileUrl);
        assertEquals(HttpStatus.OK.value(), responseFromEvidenceManagement.getStatusCode());
        assertEquals(fileToUpload, responseFromEvidenceManagement.getBody().path("originalDocumentName"));
        assertEquals(fileContentType, responseFromEvidenceManagement.getBody().path("mimeType"));
    }

    public Response readDataFromEvidenceManagement(String uri) {
        String username = "simulate-delivered" + UUID.randomUUID() + "@notifications.service.gov.uk";
        String password = UUID.randomUUID().toString().toUpperCase(Locale.UK);
        idamTestSupportUtil.createCaseworkerUserInIdam(username, password);

        Map<String, Object> headers = new HashMap<>();
        headers.put("ServiceAuthorization", authTokenGenerator.generate());
        headers.put("user-id", username);
        headers.put("user-roles", "caseworker-divorce");

        return given()
                .contentType("application/json")
                .headers(headers)
                .when()
                .get(uri)
                .andReturn();
    }

    private Map<String, Object> getAuthenticationTokenHeader() {
        String authenticationToken = idamTestSupportUtil.getIdamTestUser();
        System.out.println("Authentication Token---->" + authenticationToken);

        Map<String, Object> headers = new HashMap<>();
        headers.put("Authorization", authenticationToken);
        headers.put("Content-Type", "multipart/form-data");

        return headers;
    }
}
