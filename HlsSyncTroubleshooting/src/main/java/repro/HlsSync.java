package repro;

import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** Upload a prepared, self-contained static HLS tree. Does not fetch remote URLs. */
public final class HlsSync {
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }
    public static String contentType(Path file) {
        String name = file.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".mp4") || name.endsWith(".m4s")) return "video/mp4";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".vtt")) return "text/vtt";
        return "application/octet-stream";
    }
    private static void validatePlaylists(Path root, List<Path> files) throws Exception {
        var attribute = java.util.regex.Pattern.compile("(?:^|[:,])URI=\"([^\"]+)\"");
        for (Path file : files) {
            if (!file.toString().toLowerCase(Locale.ROOT).endsWith(".m3u8")) continue;
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty() || !lines.get(0).equals("#EXTM3U")) throw new IllegalArgumentException("Invalid playlist: " + root.relativize(file));
            for (String line : lines) {
                List<String> references = new ArrayList<>();
                if (!line.isBlank() && !line.startsWith("#")) references.add(line.trim());
                var matcher = attribute.matcher(line);
                while (matcher.find()) references.add(matcher.group(1));
                for (String reference : references) {
                    URI uri = URI.create(reference);
                    if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getPath() == null || uri.getPath().startsWith("/") || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPath().isBlank())
                        throw new IllegalArgumentException("Playlist needs localization of external, absolute or signed references: " + root.relativize(file));
                    Path dependency = file.getParent().resolve(uri.getPath()).normalize();
                    if (!dependency.startsWith(root) || !files.contains(dependency))
                        throw new IllegalArgumentException("Missing or escaping playlist dependency in " + root.relativize(file));
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: HlsSync LOCAL_DIRECTORY UNIQUE_OBJECT_PREFIX");
        uploadDirectory(Path.of(args[0]), args[1], System.out::println);
    }
    public static void uploadDirectory(Path directory, String prefix, java.util.function.Consumer<String> progress) throws Exception {
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Expected directory");
        if (!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) throw new IllegalArgumentException("Use a nonempty relative prefix with letters, numbers, _ or -");
        URI endpoint = URI.create(required("LINODE_ENDPOINT"));
        if (!"https".equals(endpoint.getScheme()) && !("http".equals(endpoint.getScheme()) && Set.of("127.0.0.1", "localhost").contains(endpoint.getHost())))
            throw new IllegalArgumentException("Endpoint must use HTTPS (HTTP allowed only for loopback tests)");
        String bucket = required("LINODE_BUCKET");
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        if (files.isEmpty()) throw new IllegalArgumentException("No files to upload");
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Only regular files are allowed: " + root.relativize(file));
        }
        validatePlaylists(root, files);
        // Publish dependencies first, then playlists. Use a fresh prefix for each run.
        files = new ArrayList<>(files);
        files.sort(Comparator.comparing((Path p) -> p.toString().toLowerCase(Locale.ROOT).endsWith(".m3u8")).thenComparing(Path::toString));
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint).region(Region.of(required("LINODE_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("LINODE_ACCESS_KEY"), required("LINODE_SECRET_KEY"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            for (Path file : files) {
                String key = prefix + "/" + root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType(file)).build(), file);
                // Stream the read-back, so large mp4s do not need to fit in memory.
                MessageDigest local = MessageDigest.getInstance("SHA-256"), remote = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), local)); }
                try (var input = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                    if (!contentType(file).equals(input.response().contentType())) throw new IllegalStateException("Content-Type mismatch: " + key);
                    input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), remote));
                }
                if (!MessageDigest.isEqual(local.digest(), remote.digest())) throw new IllegalStateException("SHA-256 mismatch: " + key);
                progress.accept("Verified: " + key + " (" + contentType(file) + ")");
            }
        }
    }
}
