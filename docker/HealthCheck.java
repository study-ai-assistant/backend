import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class HealthCheck {
    public static void main(String[] args) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/health"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().equals("{\"status\":\"ok\"}")) {
            System.exit(1);
        }
    }
}
